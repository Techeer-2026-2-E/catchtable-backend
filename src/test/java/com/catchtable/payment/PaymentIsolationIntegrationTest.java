package com.catchtable.payment;

import com.catchtable.payment.client.*;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.repository.PaymentReconcileJobRepository;
import com.catchtable.payment.service.*;
import com.catchtable.reservation.service.ReservationPaymentService;
import com.catchtable.reservation.service.ReservationRefundExecutionService;
import com.catchtable.global.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL + real loopback HTTP transport. No provider mocks or real Toss keys. */
@SpringBootTest(properties={"toss.base-url=http://127.0.0.1:19084", "toss.secret-key=",
        "toss.connect-timeout=200ms", "toss.read-timeout=300ms", "toss.call-timeout=500ms", "toss.max-concurrent-calls=1",
        "payment.jobs.enabled=true", "payment.jobs.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@Import({ReconcileTestClock.Config.class, ConfirmedEventCounter.Config.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class PaymentIsolationIntegrationTest {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    static final ObjectMapper JSON = new ObjectMapper();
    static HttpServer server;
    static ExecutorService httpThreads;
    static final Map<String, Ledger> ledger = new ConcurrentHashMap<>();
    static final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), calls = new AtomicInteger();
    static volatile int delay;
    static volatile int error;
    static volatile boolean loseMutationResponse;
    static volatile boolean trickle;
    static volatile CountDownLatch entered;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired ReconcileTestClock clock;
    @Autowired MockMvc mvc;
    @SpyBean PaymentCallGate gate;
    @Autowired PaymentService paymentService;
    @Autowired TossPaymentsClient client;
    @Autowired ReservationPaymentService approval;
    @Autowired ReservationRefundExecutionService reservationRefund;
    @Autowired PaymentRefundService refunds;
    @Autowired PaymentReconcileService reconciler;
    @Autowired PaymentReconcileJobRepository jobs;
    @Autowired PaymentReconcileJobWorker worker;
    @Autowired ConfirmedEventCounter events;
    PaymentTestData data;
    Long customer, owner, store, reservation, payment;
    String order, key;

    @BeforeAll static void startPg() throws Exception {
        httpThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",19084),0);
        server.setExecutor(httpThreads);
        server.createContext("/v1/payments", exchange -> {
            int running = active.incrementAndGet(); peak.accumulateAndGet(running, Math::max); calls.incrementAndGet();
            entered.countDown();
            try {
                String path = exchange.getRequestURI().getPath();
                boolean mutation = exchange.getRequestMethod().equals("POST");
                var body = mutation ? JSON.readTree(exchange.getRequestBody().readAllBytes()) : null;
                Ledger row;
                if (path.endsWith("/confirm")) row = ledger.get(body.get("paymentKey").asText());
                else if (path.contains("/orders/")) row = ledger.values().stream()
                        .filter(l -> path.endsWith("/" + l.order)).findFirst().orElse(null);
                else row = ledger.get(path.split("/")[3]);
                if (delay > 0) Thread.sleep(delay);
                if (error != 0) {
                    byte[] bytes = "{\"code\":\"FAKE_REJECT\",\"message\":\"fake\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(error,bytes.length); exchange.getResponseBody().write(bytes); return;
                }
                if (row == null) { exchange.sendResponseHeaders(404,-1); return; }
                synchronized (row) {
                    if (mutation) {
                        String idempotency = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                        if (row.keys.add(idempotency)) {
                            if (path.endsWith("/confirm")) { row.status="DONE"; row.approvals++; }
                            else { row.refunded += body.get("cancelAmount").asLong(); row.cancels++;
                                row.status=row.refunded == 10000 ? "CANCELED" : "PARTIAL_CANCELED"; }
                        }
                        if (loseMutationResponse) return; // committed separate PG ledger; HTTP response lost
                    }
                    byte[] bytes = JSON.writeValueAsBytes(Map.of("paymentKey",row.key,"orderId",row.order,
                            "status",row.status,"totalAmount",10000,"balanceAmount",10000-row.refunded,
                            "method","카드","approvedAt","2030-01-20T12:00:00+09:00"));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,bytes.length);
                    if (trickle) {
                        for (int i=0;i<bytes.length;i+=30) {
                            exchange.getResponseBody().write(bytes,i,Math.min(30,bytes.length-i));
                            exchange.getResponseBody().flush(); Thread.sleep(150);
                        }
                    } else exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* expected client read timeout */ }
            finally { exchange.close(); active.decrementAndGet(); }
        });
        server.start();
    }
    @AfterAll static void stopPg() {
        server.stop(0); httpThreads.shutdownNow();
    }
    @BeforeEach void setup() {
        clock.set(NOW); ledger.clear(); delay=0; error=0; loseMutationResponse=false; trickle=false;
        entered=new CountDownLatch(1); calls.set(0); peak.set(0);
        data=new PaymentTestData(jdbc); customer=data.member("CUSTOMER"); owner=data.member("OWNER"); store=data.store(owner);
        reservation=data.hold(customer,store,0); payment=data.payment(customer,reservation,"READY",0);
        order=data.orderId(payment); key="local-key-"+payment;
        jdbc.update("UPDATE reservation SET requested_at=?,hold_expires_at=?,deposit_amount_snapshot=10000,refund_policy_snapshot='FLEXIBLE' WHERE id=?",
                NOW.minusMinutes(1),NOW.plusMinutes(4),reservation);
        ledger.put(key,new Ledger(key,order));
    }
    @AfterEach void cleanup() {
        await().atMost(Duration.ofSeconds(3)).until(() -> active.get()==0);
        data.deleteStore(store,List.of(customer,owner));
    }

    @Test void admissionRejectsAllFourOperationsWithoutHttp() throws Exception {
        try (Busy busy = new Busy(gate)) {
            assertThatThrownBy(() -> client.confirm(key,order,10000,"confirm-"+order)).isInstanceOf(PaymentCapacityExceededException.class);
            assertThatThrownBy(() -> client.cancel(key,1000L,"고객 요청","refund")).isInstanceOf(PaymentCapacityExceededException.class);
            assertThatThrownBy(() -> client.getByPaymentKey(key)).isInstanceOf(PaymentCapacityExceededException.class);
            assertThatThrownBy(() -> client.getByOrderId(order)).isInstanceOf(PaymentCapacityExceededException.class);
            assertThat(calls.get()).isZero();
        }
        client.getByOrderId(order);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void approvalAdmissionLeavesNewHoldUnprotectedButKeepsExistingUnknownProtection() throws Exception {
        try (Busy busy = new Busy(gate)) {
            mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id",customer)
                            .contentType("application/json").content(JSON.writeValueAsString(new PaymentConfirmRequest(order,key,10000))))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"))
                    .andExpect(jsonPath("$.code").value("PAYMENT_CAPACITY_EXCEEDED"));
            assertThat(jdbc.queryForMap("SELECT approval_started_at,approval_payment_id,approval_order_id,approval_payment_key FROM reservation WHERE id=?",reservation).values()).containsOnlyNulls();
            assertThat(state("payment",payment)).isEqualTo("READY");
            data.protect(reservation,payment,key,NOW.minusMinutes(1));
            jdbc.update("UPDATE payment SET status='TIMEOUT' WHERE id=?",payment);
            var originalProtection=jdbc.queryForMap("SELECT approval_started_at,approval_payment_id,approval_order_id,approval_payment_key FROM reservation WHERE id=?",reservation);
            assertThatThrownBy(this::approve).isInstanceOf(PaymentCapacityExceededException.class);
            assertThat(jdbc.queryForMap("SELECT approval_started_at,approval_payment_id,approval_order_id,approval_payment_key FROM reservation WHERE id=?",reservation)).isEqualTo(originalProtection);
            assertThat(state("payment",payment)).isEqualTo("TIMEOUT");
            assertThat(calls.get()).isZero();
        }
    }

    @Test void completedApprovalReplaysWithoutAdmissionButRetainsOwnershipAndInputChecks() throws Exception {
        approve();
        int before = calls.get();
        Long foreign = data.member("CUSTOMER");
        try (Busy busy = new Busy(gate)) {
            mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id",customer)
                            .contentType("application/json").content(JSON.writeValueAsString(new PaymentConfirmRequest(order,key,10000))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.paymentId").value(payment));
            for (PaymentConfirmRequest invalid : List.of(new PaymentConfirmRequest(order,"wrong-key",10000),
                    new PaymentConfirmRequest(order,key,9999))) {
                mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id",customer)
                                .contentType("application/json").content(JSON.writeValueAsString(invalid)))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
            }
            mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id",foreign)
                            .contentType("application/json").content(JSON.writeValueAsString(new PaymentConfirmRequest(order,key,10000))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
            mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id",customer)
                            .contentType("application/json").content(JSON.writeValueAsString(new PaymentConfirmRequest(order,"",10000))))
                    .andExpect(status().isBadRequest());
            assertThat(calls.get()).isEqualTo(before);
            assertConfirmed();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id=?",Integer.class,payment)).isEqualTo(1);
        } finally { jdbc.update("DELETE FROM member WHERE id=?",foreign); }
    }

    @Test void completedReplayStillRejectsMissingApprovalProtectionAndMismatchedReservationSnapshot() throws Exception {
        approve();
        int before = calls.get();
        try (Busy busy = new Busy(gate)) {
            jdbc.update("UPDATE reservation SET deposit_amount_snapshot=9999 WHERE id=?",reservation);
            assertThatThrownBy(this::approve).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(com.catchtable.global.exception.ErrorCode.PAYMENT_MISMATCH));
            jdbc.update("UPDATE reservation SET deposit_amount_snapshot=10000,approval_started_at=NULL,approval_payment_id=NULL,approval_order_id=NULL,approval_payment_key=NULL WHERE id=?",reservation);
            assertThatThrownBy(this::approve).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(com.catchtable.global.exception.ErrorCode.PAYMENT_RECONCILIATION_REQUIRED));
            assertThat(calls.get()).isEqualTo(before);
            assertThat(state("reservation",reservation)).isEqualTo("CONFIRMED");
        }
    }

    @Test void concurrentLateApprovalCommitBetweenReplayCheckAndRejectedAdmissionReturnsReplay() throws Exception {
        // A real PG approval response has arrived, but its DB application is delayed.
        var response = client.confirm(key,order,10000,"confirm-"+order);
        data.protect(reservation,payment,key,NOW.minusMinutes(1));
        jdbc.update("UPDATE payment SET status='TIMEOUT' WHERE id=?",payment);
        CountDownLatch admissionReached = new CountDownLatch(1), continueAdmission = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("approval-replay-race")) {
                admissionReached.countDown();
                assertThat(continueAdmission.await(5,TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(gate).call(org.mockito.ArgumentMatchers.any());
        try (Busy busy = new Busy(gate); var executor = Executors.newSingleThreadExecutor(r -> new Thread(r,"approval-replay-race"))) {
            var retry = executor.submit(() -> approval.confirm(customer,new PaymentConfirmRequest(order,key,10000)));
            try {
                assertThat(admissionReached.await(2,TimeUnit.SECONDS)).isTrue();
                paymentService.applyTossPayment(payment,response,com.catchtable.payment.entity.PaymentStatusSource.TOSS_CONFIRM);
                approval.settle(payment);
            } finally { continueAdmission.countDown(); }
            assertThat(retry.get(3,TimeUnit.SECONDS).status()).isEqualTo(com.catchtable.payment.entity.PaymentStatus.DONE);
            assertConfirmed();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(ledger.get(key).approvals).isEqualTo(1);
        }
    }

    @Test void delayedApprovalHasNoDatabaseTransactionOrRowLocksAndUnknownRecovers() throws Exception {
        delay=700;
        try (var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(() -> { try { approve(); } catch (BusinessException expected) { } });
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            // A truly separate JDBC connection must be able to lock BOTH rows while PG is blocked.
            try (var connection=source.getConnection(); var statement=connection.createStatement()) {
                connection.setAutoCommit(false); statement.execute("SET LOCAL lock_timeout='100ms'");
                statement.execute("SELECT id FROM reservation WHERE id="+reservation+" FOR UPDATE NOWAIT");
                statement.execute("SELECT id FROM payment WHERE id="+payment+" FOR UPDATE NOWAIT");
                statement.execute("SELECT payment_id FROM payment_reconcile_job WHERE payment_id="+payment+" FOR UPDATE NOWAIT");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='idle in transaction'",Integer.class)).isEqualTo(1);
                connection.rollback();
            }
            assertThatThrownBy(() -> client.getByOrderId(order)).isInstanceOf(PaymentCapacityExceededException.class);
            pending.get(2,TimeUnit.SECONDS);
        }
        assertThat(state("payment",payment)).isEqualTo("TIMEOUT");
        await().atMost(Duration.ofSeconds(2)).until(() -> active.get()==0);
        delay=0; reconciler.handleWebhook(webhook()); worker.poll();
        assertConfirmed(); assertThat(ledger.get(key).approvals).isEqualTo(1);
    }

    @Test void delayedQueryAndRefundReleaseDatabaseConnectionsAndLocks() throws Exception {
        approve();
        for (boolean refund : List.of(false, true)) {
            if (!refund) {
                jdbc.update("UPDATE payment SET status='TIMEOUT',payment_key=NULL,approved_at=NULL,method=NULL WHERE id=?",payment);
            }
            delay=700; entered=new CountDownLatch(1);
            try (var executor=Executors.newSingleThreadExecutor()) {
                var pending=executor.submit(() -> {
                    try {
                        if (refund) refunds.refund(customer,payment,"locks-refund","고객 요청",3000L,10000);
                        else reconciler.reconcile(payment);
                    } catch (BusinessException expected) { }
                });
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                try (var connection=source.getConnection(); var statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    statement.execute("SELECT id FROM reservation WHERE id="+reservation+" FOR UPDATE NOWAIT");
                    statement.execute("SELECT id FROM payment WHERE id="+payment+" FOR UPDATE NOWAIT");
                    statement.execute("SELECT id FROM payment_refund WHERE payment_id="+payment+" FOR UPDATE NOWAIT");
                    connection.rollback();
                }
                pending.get(2,TimeUnit.SECONDS);
            }
            await().atMost(Duration.ofSeconds(2)).until(() -> active.get()==0);
            delay=0; reconciler.reconcile(payment);
        }
        assertThat(ledger.get(key).cancels).isEqualTo(1);
        assertThat(state("payment",payment)).isEqualTo("PARTIAL_REFUNDED");
    }

    @Test void duplicateWebhooksAreDurableAndOnlyQueryOnWorker() throws Exception {
        data.protect(reservation,payment,key,NOW.minusMinutes(1)); ledger.get(key).status="DONE";
        for (int i=0;i<10;i++) mvc.perform(post("/api/user/webhooks/payments").contentType("application/json").content(webhook()))
                .andExpect(status().isOk());
        assertThat(calls.get()).isZero(); assertThat(state("payment",payment)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_reconcile_job WHERE payment_id=?",Integer.class,payment)).isEqualTo(1);
        worker.poll(); assertConfirmed(); assertThat(calls.get()).isEqualTo(1);
        assertThat(state("payment_reconcile_job",payment)).isEqualTo("DONE");
    }

    @Test void leaseExpiryAndConcurrentConsumersFenceOldAcknowledgements() throws Exception {
        jobs.enqueue(payment,NOW,true);
        var first=jobs.claim(NOW).orElseThrow();
        assertThat(jobs.claim(NOW.plusSeconds(119))).isEmpty();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> jobs.claim(NOW.plusSeconds(120)));
            var b=pool.submit(() -> jobs.claim(NOW.plusSeconds(120)));
            var claims=List.of(a.get(),b.get()).stream().flatMap(Optional::stream).toList();
            assertThat(claims).hasSize(1);
            jobs.finish(first,NOW.plusSeconds(120),true,null);
            assertThat(state("payment_reconcile_job",payment)).isEqualTo("PROCESSING");
            jobs.finish(claims.getFirst(),NOW.plusSeconds(120),true,null);
            assertThat(state("payment_reconcile_job",payment)).isEqualTo("DONE");
        }
    }

    @Test void pgErrorRetryAndRestartAfterClaimReusePersistedJob() {
        data.protect(reservation,payment,key,NOW.minusMinutes(1)); ledger.get(key).status="DONE";
        jobs.enqueue(payment,NOW,true); error=500; worker.poll();
        assertThat(state("payment",payment)).isEqualTo("READY");
        assertThat(state("payment_reconcile_job",payment)).isEqualTo("PENDING");
        assertThat(jobs.claim(NOW)).isEmpty();
        clock.set(NOW.plusSeconds(2)); var abandoned=jobs.claim(NOW.plusSeconds(2)).orElseThrow();
        // Simulate loss of the worker object/process after durable claim, reconstruct the repository after lease.
        var restarted=new PaymentReconcileJobRepository(jdbc);
        var next=restarted.claim(NOW.plusSeconds(122)).orElseThrow();
        assertThat(next.attempt()).isGreaterThan(abandoned.attempt());
        error=0; clock.set(NOW.plusSeconds(122));
        restarted.finish(next,OffsetDateTime.now(clock),reconciler.reconcile(payment),null);
        assertConfirmed();
    }

    @Test void refundAdmissionPreservesIntentAmountKeyAndRecovery() throws Exception {
        approve();
        try (Busy busy = new Busy(gate)) {
            assertThatThrownBy(() -> reservationRefund.refund(customer,payment,"original-refund","고객 요청",3000L))
                    .isInstanceOf(PaymentCapacityExceededException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_refund WHERE payment_id=?",String.class,payment)).isEqualTo("REQUESTED");
            assertThat(jdbc.queryForObject("SELECT status FROM reservation_refund_request WHERE payment_id=?",String.class,payment)).isEqualTo("REQUESTED");
            assertThat(jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id=?",Boolean.class,reservation)).isTrue();
            assertThat(ledger.get(key).cancels).isZero();
        }
        reconciler.handleWebhook(webhook()); worker.poll();
        assertThat(state("payment",payment)).isEqualTo("PARTIAL_REFUNDED");
        assertThat(ledger.get(key).refunded).isEqualTo(3000); assertThat(ledger.get(key).cancels).isEqualTo(1);
        assertThat(ledger.get(key).keys).contains("original-refund");
        assertThat(jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id=?",Boolean.class,reservation)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_refund_request WHERE payment_id=?",String.class,payment)).isEqualTo("COMPLETED");
    }

    @Test void responseLossOnApprovalAndRefundRecoversFromIndependentPgLedger() {
        loseMutationResponse=true;
        assertThatThrownBy(this::approve).isInstanceOf(BusinessException.class);
        assertThat(state("payment",payment)).isEqualTo("TIMEOUT");
        loseMutationResponse=false; jobs.enqueue(payment,NOW,true); worker.poll(); assertConfirmed();
        loseMutationResponse=true;
        assertThatThrownBy(() -> refunds.refund(customer,payment,"lost-refund","고객 요청",10000L,10000))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_refund WHERE payment_id=?",String.class,payment)).isEqualTo("UNKNOWN");
        loseMutationResponse=false; jobs.enqueue(payment,NOW,true); worker.poll();
        assertThat(state("payment",payment)).isEqualTo("REFUNDED");
        assertThat(ledger.get(key).approvals).isEqualTo(1); assertThat(ledger.get(key).cancels).isEqualTo(1);
    }

    @Test void sameKeyRefundRetryBoundaryPreservesFifteenDayRule() {
        approve();
        Long refund=data.inFlightRefund(payment,"old-key",3000,"UNKNOWN",NOW.minusDays(15));
        jobs.enqueue(payment,NOW,true); worker.poll();
        assertThat(state("payment_refund",refund)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment_refund WHERE id=?",String.class,refund)).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(ledger.get(key).cancels).isZero();
    }

    @Test void storageFailureIsNotAcknowledgedAndMalformedInputStillIs() throws Exception {
        jdbc.execute("ALTER TABLE payment_reconcile_job ADD CONSTRAINT isolation_reject_insert CHECK (payment_id<>"+payment+")");
        try {
            mvc.perform(post("/api/user/webhooks/payments").contentType("application/json").content(webhook()))
                    .andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_reconcile_job WHERE payment_id=?",Integer.class,payment)).isZero();
        } finally { jdbc.execute("ALTER TABLE payment_reconcile_job DROP CONSTRAINT isolation_reject_insert"); }
        for (String body:List.of("", "not json", "null", "[]", "{\"orderId\":\"missing\"}")) {
            mvc.perform(post("/api/user/webhooks/payments").contentType("application/json").content(body)).andExpect(status().isOk());
        }
        assertThat(calls.get()).isZero();
    }

    @Test void totalDeadlineBoundsSlowBodyAndRetainsPhysicalCapacityUntilHttpStops() {
        trickle=true;
        long started=System.nanoTime();
        assertThatThrownBy(() -> client.getByOrderId(order)).isInstanceOf(TossPaymentsUnknownResultException.class);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
        assertThat(active.get()).isEqualTo(1);
        // Slow streaming continues after the caller's deadline, so the physical capacity must stay occupied.
        assertThatThrownBy(this::approve).isInstanceOf(PaymentCapacityExceededException.class);
        assertThat(jdbc.queryForObject("SELECT approval_payment_id FROM reservation WHERE id=?",Long.class,reservation)).isNull();
        await().atMost(Duration.ofSeconds(3)).until(() -> active.get()==0);
        trickle=false;
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(client.getByOrderId(order)).isNotNull());
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test void webhookDuringClaimSurvivesSuccessfulOldCompletion() {
        jobs.enqueue(payment,NOW,true); var first=jobs.claim(NOW).orElseThrow();
        jobs.enqueue(payment,NOW.plusSeconds(1),true);
        jobs.finish(first,NOW.plusSeconds(2),true,null);
        assertThat(state("payment_reconcile_job",payment)).isEqualTo("PENDING");
        var second=jobs.claim(NOW.plusSeconds(2)).orElseThrow();
        assertThat(second.version()).isGreaterThan(first.version());
        jobs.finish(second,NOW.plusSeconds(2),true,null);
        assertThat(state("payment_reconcile_job",payment)).isEqualTo("DONE");
    }

    void approve() { approval.confirm(customer,new PaymentConfirmRequest(order,key,10000)); }
    String webhook() { return "{\"data\":{\"orderId\":\""+order+"\",\"status\":\"CANCELED\"}}"; }
    String state(String table,Long id) { return jdbc.queryForObject("SELECT status FROM "+table+" WHERE "+(table.equals("payment_reconcile_job")?"payment_id":"id")+"=?",String.class,id); }
    void assertConfirmed() {
        assertThat(state("payment",payment)).isEqualTo("DONE");
        assertThat(state("reservation",reservation)).isEqualTo("CONFIRMED");
        assertThat(events.count(reservation)).isEqualTo(1);
    }
    static class Ledger {
        final String key,order; String status="IN_PROGRESS"; long refunded; int approvals,cancels;
        final Set<String> keys=new HashSet<>();
        Ledger(String key,String order) { this.key=key; this.order=order; }
    }
    static class Busy implements AutoCloseable {
        final CountDownLatch release=new CountDownLatch(1);
        final ExecutorService thread=Executors.newSingleThreadExecutor();
        final Future<?> future;
        Busy(PaymentCallGate gate) throws Exception {
            var acquired=new CountDownLatch(1);
            future=thread.submit(() -> gate.call(() -> {
                acquired.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("busy timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }));
            assertThat(acquired.await(2,TimeUnit.SECONDS)).isTrue();
        }
        public void close() throws Exception { release.countDown(); future.get(2,TimeUnit.SECONDS); thread.close(); }
    }
}
