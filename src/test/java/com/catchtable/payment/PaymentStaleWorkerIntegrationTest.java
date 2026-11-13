package com.catchtable.payment;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.service.*;
import com.catchtable.payment.repository.PaymentReconcileJobRepository;
import com.catchtable.reservation.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Keeps the ORIGINAL worker alive beyond its Clock lease, including real stale HTTP responses. */
@SpringBootTest(properties={"toss.base-url=http://127.0.0.1:19084","toss.secret-key=",
        "toss.connect-timeout=1s","toss.read-timeout=15s","toss.call-timeout=18s","toss.max-concurrent-calls=8",
        "payment.jobs.enabled=true","payment.jobs.initial-delay-ms=3600000","payment.jobs.batch-size=1",
        "reservation.refund-outbox.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import({ReconcileTestClock.Config.class, ConfirmedEventCounter.Config.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class PaymentStaleWorkerIntegrationTest {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    static final ObjectMapper JSON=new ObjectMapper();
    static HttpServer server;
    static ExecutorService httpThreads;
    static final Map<String,Ledger> ledger=new ConcurrentHashMap<>();
    static final Map<String,Pause> beforeEffect=new ConcurrentHashMap<>(), afterSnapshot=new ConcurrentHashMap<>();
    static final List<Pause> pauses=new CopyOnWriteArrayList<>();
    static final List<Call> calls=new CopyOnWriteArrayList<>();
    static final Queue<Throwable> serverErrors=new ConcurrentLinkedQueue<>();
    static final AtomicInteger active=new AtomicInteger();
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired MockMvc mvc;
    @Autowired ReconcileTestClock clock;
    @Autowired ReservationPaymentService approval;
    @Autowired PaymentApprovalService commonApproval;
    @Autowired ReservationRefundExecutionService refund;
    @Autowired ReservationUnconfirmedRefundService unconfirmed;
    @Autowired ReservationRefundOutboxDispatcher dispatcher;
    @Autowired PaymentReconcileService reconciler;
    @Autowired PaymentReconcileJobWorker worker;
    @Autowired PaymentReconcileJobRepository jobs;
    @Autowired ConfirmedEventCounter events;
    PaymentTestData data;
    Long customer,owner,store,reservation,payment;
    String key,order;

    @BeforeAll static void startPg() throws Exception {
        httpThreads=Executors.newCachedThreadPool();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",19084),0);
        server.setExecutor(httpThreads);
        server.createContext("/v1/payments", exchange -> {
            active.incrementAndGet();
            try {
                String path=exchange.getRequestURI().getPath();
                String operation=exchange.getRequestMethod().equals("GET") ? "query" : path.endsWith("/confirm") ? "confirm" : "cancel";
                var body=operation.equals("query") ? null : JSON.readTree(exchange.getRequestBody().readAllBytes());
                String idem=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                long amount=body==null ? 0 : body.get(operation.equals("confirm") ? "amount" : "cancelAmount").asLong();
                calls.add(new Call(operation,idem,amount));
                Ledger row=operation.equals("confirm") ? ledger.get(body.get("paymentKey").asText())
                        : path.contains("/orders/") ? ledger.values().stream().filter(r -> path.endsWith("/"+r.order)).findFirst().orElseThrow()
                        : ledger.get(path.split("/")[3]);
                Pause before=beforeEffect.remove(operation);
                if (before!=null) before.block();
                Map<String,Object> response;
                synchronized (row) {
                    if (operation.equals("query")) response=row.snapshot();
                    else {
                        response=row.results.get(idem);
                        if (response==null) {
                            if (operation.equals("confirm")) { row.status="DONE"; row.approvals++; }
                            else { row.refunded+=amount; row.cancels++; row.status=row.refunded==10000 ? "CANCELED" : "PARTIAL_CANCELED"; }
                            response=row.snapshot(); row.results.put(idem,response);
                        }
                    }
                }
                // Freeze the response bytes BEFORE waiting, without holding the external ledger lock.
                byte[] bytes=JSON.writeValueAsBytes(response);
                Pause delayed=afterSnapshot.remove(operation);
                if (delayed!=null) delayed.block();
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) { serverErrors.add(failure); }
            finally { exchange.close(); active.decrementAndGet(); }
        });
        server.start();
    }
    @AfterAll static void stopPg() { server.stop(0); httpThreads.shutdownNow(); }
    @BeforeEach void setup() {
        clock.set(NOW); ledger.clear(); calls.clear(); serverErrors.clear(); pauses.clear(); beforeEffect.clear(); afterSnapshot.clear();
        data=new PaymentTestData(jdbc);customer=data.member("CUSTOMER");owner=data.member("OWNER");store=data.store(owner);
        reservation=data.hold(customer,store,0);payment=data.payment(customer,reservation,"READY",0);
        order=data.orderId(payment);key="stale-pg-key-"+payment;
        jdbc.update("UPDATE reservation SET requested_at=?,hold_expires_at=?,deposit_amount_snapshot=10000,refund_policy_snapshot='FLEXIBLE' WHERE id=?",
                NOW.minusMinutes(1),NOW.plusMinutes(4),reservation);
        ledger.put(key,new Ledger(key,order));
    }
    @AfterEach void cleanup() {
        pauses.forEach(Pause::release);
        await().atMost(Duration.ofSeconds(5)).until(() -> active.get()==0);
        data.deleteStore(store,List.of(customer,owner));
        assertThat(serverErrors).isEmpty();
    }

    @Test void liveOldApprovalWorkerAndLateConfirmCannotOverwriteNewWorkerSyncAndPartialRefund() throws Exception {
        Pause confirmResponse=pause(afterSnapshot,"confirm"), oldQuery=pause(afterSnapshot,"query");
        try (var pool=Executors.newFixedThreadPool(4)) {
            var confirming=pool.submit(() -> approval.confirm(customer,new PaymentConfirmRequest(order,key,10000)));
            try {
                confirmResponse.awaitEntered(); // PG approval effect happened; app has not received it.
                jobs.enqueue(payment,NOW,true);
                var staleWorker=pool.submit(worker::poll);
                try {
                    oldQuery.awaitEntered(); // Old job has captured DONE but remains alive at PG.
                    assertThat(job().get("attempt_count")).isEqualTo(1L);
                    assertNoDatabaseLocksDuringPg();
                    clock.set(NOW.plusSeconds(121));
                    var replacement=pool.submit(worker::poll);
                    var userSync=pool.submit(() -> { sync(); return null; });
                    replacement.get(5,TimeUnit.SECONDS); userSync.get(5,TimeUnit.SECONDS);
                    assertThat(state("payment",payment)).isEqualTo("DONE");
                    assertThat(state("reservation",reservation)).isEqualTo("CONFIRMED");
                    refund.refund(customer,payment,"original-partial-refund","고객 요청",3000L);
                    Map<String,Object> latestJob=job();
                    Map<String,Object> latestPayment=paymentState();
                    Map<String,Object> latestRefund=refundState();
                    Map<String,Object> latestReservation=jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",reservation);
                    assertThat(latestJob.get("attempt_count")).isEqualTo(2L);
                    assertThat(latestJob.get("status")).isEqualTo("DONE");
                    assertThat(latestPayment.get("status")).isEqualTo("PARTIAL_REFUNDED");
                    oldQuery.release(); staleWorker.get(5,TimeUnit.SECONDS);
                    // This assertion checks domain state as well as the ACK fence.
                    assertThat(job()).isEqualTo(latestJob);
                    assertThat(paymentState()).isEqualTo(latestPayment);
                    assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",reservation)).isEqualTo(latestReservation);
                    assertThat(refundState()).isEqualTo(latestRefund);
                    confirmResponse.release();
                    // The original synchronous confirm now observes a subsequent refund, so its old response
                    // cannot pretend the latest state is DONE or re-confirm the reservation.
                    assertThatThrownBy(() -> confirming.get(5,TimeUnit.SECONDS))
                            .isInstanceOfSatisfying(ExecutionException.class, failure ->
                                    assertThat(failure.getCause()).isInstanceOfSatisfying(BusinessException.class,
                                            business -> assertThat(business.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED)));
                    assertThat(paymentState()).isEqualTo(latestPayment);
                    assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",reservation)).isEqualTo(latestReservation);
                    assertThat(refundState()).isEqualTo(latestRefund);
                    assertThat(state("reservation",reservation)).isEqualTo("CONFIRMED");
                    assertThat(events.count(reservation)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id=?",Integer.class,payment)).isEqualTo(2);
                    assertRefundOriginal("PAYMENT_ONLY","original-partial-refund",3000);
                    assertEffects(3000);
                } finally { oldQuery.release(); }
            } finally { confirmResponse.release(); }
        }
    }

    @Test void liveOldJobAndV33DispatcherCannotOverwriteReclaimedJobOutboxAndUserSync() throws Exception {
        // Real orphan approval after hold expiry -> existing reconciliation marks it for refund.
        jdbc.update("UPDATE reservation SET hold_expires_at=? WHERE id=?",NOW.minusSeconds(1),reservation);
        commonApproval.approve(customer,order,key,10000);
        reconciler.reconcile(payment);
        assertThat(state("reservation",reservation)).isEqualTo("EXPIRED");
        Long replacementHold=data.hold(customer,store,0);
        var accepted=unconfirmed.request(customer,payment,"original-unconfirmed-refund");
        Long requestId=accepted.response().requestId();
        Map<String,Object> originalIntent=intent();
        Pause firstCancel=pause(beforeEffect,"cancel"), oldQuery=pause(afterSnapshot,"query");
        try (var pool=Executors.newFixedThreadPool(5)) {
            var staleOutbox=pool.submit(dispatcher::poll);
            try {
                firstCancel.awaitEntered(); // REQUESTED is committed but the first cancel hasn't affected PG yet.
                jobs.enqueue(payment,NOW,true);
                var staleWorker=pool.submit(worker::poll);
                try {
                    oldQuery.awaitEntered(); // Captured pre-refund DONE, not the eventual REFUNDED response.
                    assertThat(refundState().get("status")).isEqualTo("REQUESTED");
                    assertNoDatabaseLocksDuringPg();
                    clock.set(NOW.plusSeconds(121));
                    var replacement=pool.submit(worker::poll);
                    var userSync=pool.submit(() -> { sync(); return null; });
                    var replacementOutbox=pool.submit(dispatcher::poll);
                    replacement.get(5,TimeUnit.SECONDS);userSync.get(5,TimeUnit.SECONDS);replacementOutbox.get(5,TimeUnit.SECONDS);
                    Map<String,Object> latestJob=job(), latestPayment=paymentState(), latestRefund=refundState();
                    Map<String,Object> latestReservation=jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",reservation);
                    Map<String,Object> latestReplacement=jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",replacementHold);
                    Map<String,Object> latestOutbox=jdbc.queryForMap("SELECT * FROM reservation_refund_outbox WHERE request_id=?",requestId);
                    assertThat(latestJob.get("attempt_count")).isEqualTo(2L);
                    assertThat(latestJob.get("status")).isEqualTo("DONE");
                    assertThat(latestOutbox.get("attempt_count")).isEqualTo(2);
                    assertThat(latestOutbox.get("status")).isEqualTo("DELIVERED");
                    assertThat(latestRefund.get("status")).isEqualTo("COMPLETED");
                    assertThat(latestPayment.get("status")).isEqualTo("REFUNDED");
                    assertThat(calls.stream().filter(c -> c.operation().equals("cancel")).count()).isGreaterThanOrEqualTo(2);
                    assertThat(intent()).isEqualTo(originalIntent);
                    oldQuery.release();staleWorker.get(5,TimeUnit.SECONDS);
                    firstCancel.release();staleOutbox.get(5,TimeUnit.SECONDS);
                    assertThat(job()).isEqualTo(latestJob);
                    assertThat(jdbc.queryForMap("SELECT * FROM reservation_refund_outbox WHERE request_id=?",requestId)).isEqualTo(latestOutbox);
                    assertThat(paymentState()).isEqualTo(latestPayment);
                    assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",reservation)).isEqualTo(latestReservation);
                    assertThat(refundState()).isEqualTo(latestRefund);
                    assertThat(intent()).isEqualTo(originalIntent);
                    assertThat(state("reservation",reservation)).isEqualTo("EXPIRED");
                    assertThat(state("reservation",replacementHold)).isEqualTo("PENDING_PAYMENT");
                    assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id=?",replacementHold)).isEqualTo(latestReplacement);
                    assertThat(jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id=?",Boolean.class,reservation)).isFalse();
                    assertThat(events.count(reservation)).isZero();
                    assertRefundOriginal("UNCONFIRMED_REFUND","original-unconfirmed-refund",10000);
                    assertEffects(10000);
                } finally { oldQuery.release(); }
            } finally { firstCancel.release(); }
        }
    }

    void sync() throws Exception {
        mvc.perform(post("/api/user/payments/{id}/sync",payment).header("X-Member-Id",customer)).andExpect(status().isOk());
    }
    void assertNoDatabaseLocksDuringPg() throws Exception {
        try (var connection=source.getConnection();var statement=connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT id FROM reservation WHERE id="+reservation+" FOR UPDATE NOWAIT");
            statement.execute("SELECT id FROM payment WHERE id="+payment+" FOR UPDATE NOWAIT");
            statement.execute("SELECT id FROM payment_refund WHERE payment_id="+payment+" FOR UPDATE NOWAIT");
            statement.execute("SELECT id FROM reservation_refund_request WHERE payment_id="+payment+" FOR UPDATE NOWAIT");
            statement.execute("SELECT payment_id FROM payment_reconcile_job WHERE payment_id="+payment+" FOR UPDATE NOWAIT");
            statement.execute("SELECT o.request_id FROM reservation_refund_outbox o JOIN reservation_refund_request r ON r.id=o.request_id WHERE r.payment_id="+payment+" FOR UPDATE OF o NOWAIT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'",Integer.class)).isEqualTo(1);
            connection.rollback();
        }
    }
    void assertRefundOriginal(String intent,String originalKey,long amount) {
        assertThat(jdbc.queryForObject("SELECT intent FROM reservation_refund_request WHERE payment_id=?",String.class,payment)).isEqualTo(intent);
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_refund_request WHERE payment_id=?",String.class,payment)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM payment_refund WHERE payment_id=?",String.class,payment)).isEqualTo(originalKey);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payment_refund WHERE payment_id=?",Long.class,payment)).isEqualTo(amount);
        assertThat(calls.stream().filter(c -> c.operation().equals("cancel")).toList())
                .allSatisfy(c -> {assertThat(c.idempotencyKey()).isEqualTo(originalKey);assertThat(c.amount()).isEqualTo(amount);});
    }
    void assertEffects(long refunded) {
        Ledger row=ledger.get(key);
        synchronized (row) {
            assertThat(row.approvals).isEqualTo(1);assertThat(row.cancels).isEqualTo(1);assertThat(row.refunded).isEqualTo(refunded);
        }
    }
    Map<String,Object> job() {return jdbc.queryForMap("SELECT * FROM payment_reconcile_job WHERE payment_id=?",payment);}
    Map<String,Object> paymentState() {return jdbc.queryForMap("SELECT * FROM payment WHERE id=?",payment);}
    Map<String,Object> refundState() {return jdbc.queryForMap("SELECT * FROM payment_refund WHERE payment_id=?",payment);}
    Map<String,Object> intent() {return jdbc.queryForMap("SELECT intent,idempotency_key,requested_amount,refund_amount,policy_refund_limit,requested_at,cancel_reason FROM reservation_refund_request WHERE payment_id=?",payment);}
    String state(String table,Long id) {return jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id);}
    static Pause pause(Map<String,Pause> where,String operation) {Pause pause=new Pause();where.put(operation,pause);pauses.add(pause);return pause;}
    static class Pause {
        final CountDownLatch entered=new CountDownLatch(1), released=new CountDownLatch(1);
        void block() throws Exception {entered.countDown();if (!released.await(10,TimeUnit.SECONDS)) throw new AssertionError("test did not release delayed PG response");}
        void awaitEntered() throws Exception {assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();}
        void release() {released.countDown();}
    }
    record Call(String operation,String idempotencyKey,long amount) {}
    static class Ledger {
        final String key,order;String status="IN_PROGRESS";long refunded;int approvals,cancels;
        final Map<String,Map<String,Object>> results=new HashMap<>();
        Ledger(String key,String order) {this.key=key;this.order=order;}
        Map<String,Object> snapshot() {return Map.of("paymentKey",key,"orderId",order,"status",status,
                "totalAmount",10000,"balanceAmount",10000-refunded,"method","카드","approvedAt",NOW.toString());}
    }
}
