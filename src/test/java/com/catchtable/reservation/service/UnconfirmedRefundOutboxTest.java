package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.ReconcileTestClock;
import com.catchtable.payment.service.PaymentReconcileService;
import com.catchtable.payment.service.PaymentRefundService;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.entity.ReservationStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.catchtable.reservation.repository.ReservationRefundOutboxRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "reservation.refund-outbox.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import({FakeTossPaymentsClient.Config.class, ReconcileTestClock.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnconfirmedRefundOutboxTest {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    static final String REASON = "예약 미확정 결제 전액 환불";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ReservationRefundOutboxDispatcher dispatcher;
    @Autowired ReservationRefundOutboxRepository outbox;
    @Autowired TransactionTemplate transactions;
    @Autowired ReconcileTestClock clock;
    @Autowired ReservationService reservations;
    @Autowired PaymentReconcileService reconciler;
    @Autowired SseEmitterRepository emitters;
    @SpyBean FakeTossPaymentsClient toss;
    @SpyBean PaymentRefundService common;
    PaymentTestData data;
    Long customerId, ownerId, foreignId, storeId, reservationId, paymentId;
    SseEmitter emitter;

    @BeforeEach void setup() {
        clock.set(NOW);
        toss.reset();
        data = new PaymentTestData(jdbc);
        customerId = data.member("CUSTOMER"); ownerId = data.member("OWNER"); foreignId = data.member("CUSTOMER");
        storeId = data.store(ownerId);
        jdbc.update("INSERT INTO business_hour(store_id,day_of_week,open_time,closing_time) SELECT ?,d,'00:00','23:59' FROM generate_series(1,7) d", storeId);
        reservationId = data.hold(customerId, storeId, 0);
        jdbc.update("UPDATE reservation SET status='EXPIRED', deposit_amount_snapshot=10000, refund_policy_snapshot='STRICT' WHERE id=?", reservationId);
        paymentId = data.payment(customerId, reservationId, "DONE", 0);
        jdbc.update("UPDATE payment SET refund_required_at=?, refund_required_reason='예약 확정 없이 확인된 승인' WHERE id=?", NOW, paymentId);
        toss.register(data.paymentKey(paymentId), data.orderId(paymentId), 10000, 0);
        emitter = mock(SseEmitter.class); emitters.save(customerId, emitter);
    }

    @AfterEach void cleanup() {
        emitters.delete(customerId, emitter);
        jdbc.update("DELETE FROM business_hour WHERE store_id=?", storeId);
        data.deleteStore(storeId, List.of(customerId, ownerId, foreignId));
    }

    @Test void requestCommitsFullRemainingAmountAndOutboxTogetherWithoutCallingPg() throws Exception {
        enqueue("late").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.refundAmount").value(10000))
                .andExpect(jsonPath("$.refundStatus").value("REQUESTED"));
        enqueue("late").andExpect(status().isOk());
        assertThat(count("reservation_refund_request")).isEqualTo(1);
        assertThat(count("reservation_refund_outbox")).isEqualTo(1);
        assertThat(count("payment_refund")).isZero();
        assertThat(frozen()).isTrue();
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(toss.cancelKeys()).isEmpty();
        mvc.perform(get(path()).header("X-Member-Id", customerId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.refundStatus").value("REQUESTED"));
    }

    @Test void rollbackCannotLeaveACommandOrFreezeOrExternalRefund() {
        transactions.executeWithoutResult(tx -> {
            try { enqueue("rollback").andExpect(status().isAccepted()); } catch (Exception e) { throw new RuntimeException(e); }
            tx.setRollbackOnly();
        });
        assertThat(count("reservation_refund_request")).isZero();
        assertThat(count("reservation_refund_outbox")).isZero();
        assertThat(frozen()).isFalse();
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test void identityOwnershipAndKeyAreCheckedBeforeEnqueue() throws Exception {
        mvc.perform(get(path()).header("X-Member-Id", customerId)).andExpect(status().isNoContent());
        mvc.perform(get(path()).header("X-Member-Id", foreignId)).andExpect(status().isNotFound());
        mvc.perform(get(path()).header("X-Member-Id", ownerId)).andExpect(status().isForbidden());
        mvc.perform(post(path()).header("Idempotency-Key", "a")).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).header("X-Member-Id", ownerId).header("Idempotency-Key", "a"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path()).header("X-Member-Id", foreignId).header("Idempotency-Key", "a"))
                .andExpect(status().isNotFound());
        mvc.perform(post(path()).header("X-Member-Id", customerId)).andExpect(status().isBadRequest());
        enqueue(" ").andExpect(status().isBadRequest());
        assertThat(count("reservation_refund_request")).isZero();
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"CONFIRMED", "PREVIOUSLY_CONFIRMED", "UNMARKED", "PROTECTED", "MISMATCH"})
    void onlyAnUnprotectedNeverConfirmedMarkedPaymentCanQueue(String invalid) throws Exception {
        switch (invalid) {
            case "CONFIRMED" -> jdbc.update("UPDATE reservation SET status='CONFIRMED', confirmed_at=? WHERE id=?", NOW, reservationId);
            case "PREVIOUSLY_CONFIRMED" -> jdbc.update("UPDATE reservation SET status='CANCELLED', confirmed_at=? WHERE id=?", NOW, reservationId);
            case "UNMARKED" -> jdbc.update("UPDATE payment SET refund_required_at=NULL, refund_required_reason=NULL WHERE id=?", paymentId);
            case "PROTECTED" -> jdbc.update("UPDATE reservation SET approval_quarantined=true WHERE id=?", reservationId);
            case "MISMATCH" -> jdbc.update("UPDATE reservation SET deposit_amount_snapshot=9999 WHERE id=?", reservationId);
        }
        enqueue("invalid").andExpect(status().isConflict());
        assertThat(count("reservation_refund_request")).isZero();
        assertThat(frozen()).isFalse();
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test void dispatcherCompletesFullRefundWithoutChangingTerminalReservationOrSendingCancelNotification() throws Exception {
        enqueue("full").andExpect(status().isAccepted());
        poll(); poll();
        assertThat(paymentState()).isEqualTo("REFUNDED");
        assertThat(requestStatus()).isEqualTo("COMPLETED");
        assertThat(outboxStatus()).isEqualTo("DELIVERED");
        assertThat(frozen()).isFalse();
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        enqueue("full").andExpect(status().isOk()).andExpect(jsonPath("$.refundStatus").value("COMPLETED"));
        verifyNoInteractions(emitter);
    }

    @Test void aPartiallyRefundedPaymentReturnsOnlyTheFullRemainingBalance() throws Exception {
        jdbc.update("UPDATE payment SET status='PARTIAL_REFUNDED', refunded_amount=4000 WHERE id=?", paymentId);
        toss.register(data.paymentKey(paymentId), data.orderId(paymentId), 10000, 4000);
        enqueue("remaining").andExpect(status().isAccepted()).andExpect(jsonPath("$.refundAmount").value(6000));
        poll();
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id=?", Long.class, paymentId)).isEqualTo(10000);
        assertThat(toss.cancelExecutions()).isEqualTo(1);
    }

    @Test void failedKeyDoesNotCallPgAgainAndANewExplicitRequestCanRetryAfterUnfreeze() throws Exception {
        enqueue("failed").andExpect(status().isAccepted());
        toss.failNext(FakeTossPaymentsClient.rejected()); poll();
        assertThat(requestStatus()).isEqualTo("FAILED");
        assertThat(frozen()).isFalse();
        enqueue("failed").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_REFUND_FAILED"));
        poll();
        assertThat(toss.cancelKeys()).hasSize(1);
        enqueue("retry").andExpect(status().isAccepted()); poll();
        assertThat(paymentState()).isEqualTo("REFUNDED");
        assertThat(toss.cancelExecutions()).isEqualTo(1);
    }

    @Test void unknownResultKeepsFreezeAndExistingReconciliationCompletesWithoutTouchingTheReplacement() throws Exception {
        var original = reservations.getHold(customerId, reservationId);
        var replacement = reservations.createHold(customerId, new CreateReservationRequest(storeId, original.reservationStartAt(), 2));
        enqueue("unknown").andExpect(status().isAccepted());
        toss.failNext(FakeTossPaymentsClient.timeout()); poll();
        assertThat(requestStatus()).isEqualTo("UNKNOWN");
        assertThat(frozen()).isTrue();
        enqueue("other").andExpect(status().isConflict());
        reconciler.reconcile(paymentId);
        assertThat(requestStatus()).isEqualTo("COMPLETED");
        assertThat(frozen()).isFalse();
        assertThat(reservations.getHold(customerId, replacement.holdId()).status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(state()).isEqualTo("EXPIRED");
    }

    @Test void anUncommittedPgDispatchFailureIsRetriedFromTheDurableOutbox() throws Exception {
        enqueue("retry-dispatch").andExpect(status().isAccepted());
        doThrow(new IllegalStateException("local dispatch interruption")).when(common)
                .refund(eq(customerId),eq(paymentId),eq("retry-dispatch"),anyString(),any(),anyLong());
        poll();
        assertThat(outboxStatus()).isEqualTo("PENDING");
        assertThat(frozen()).isTrue();
        assertThat(count("payment_refund")).isZero();
        doCallRealMethod().when(common).refund(eq(customerId),eq(paymentId),eq("retry-dispatch"),anyString(),any(),anyLong());
        clock.set(NOW.plusMinutes(6)); poll();
        assertThat(paymentState()).isEqualTo("REFUNDED");
    }

    @Test void leaseExpiryRecoversACrashAfterClaimAndBeforePgRequest() throws Exception {
        enqueue("crash").andExpect(status().isAccepted());
        jdbc.update("UPDATE reservation_refund_outbox SET status='PROCESSING',attempt_count=1,lease_until=?", NOW.plusSeconds(120));
        poll(); assertThat(toss.cancelKeys()).isEmpty();
        clock.set(NOW.plusSeconds(120)); poll();
        assertThat(paymentState()).isEqualTo("REFUNDED");
        assertThat(outboxStatus()).isEqualTo("DELIVERED");
        assertThat(toss.cancelExecutions()).isEqualTo(1);
    }

    @Test void crashAfterCommonRequestCommitUsesExistingReconciliationAndTheSameKey() throws Exception {
        enqueue("common-crash").andExpect(status().isAccepted());
        jdbc.update("INSERT INTO payment_refund(payment_id,idempotency_key,requested_amount,cancel_reason,refund_amount,status,requested_at) VALUES (?,'common-crash',10000,?,10000,'REQUESTED',?)", paymentId, REASON, NOW);
        poll();
        assertThat(paymentState()).isEqualTo("REFUNDED");
        assertThat(count("payment_refund")).isEqualTo(1);
        assertThat(toss.cancelKeys()).containsExactly("common-crash");
    }

    @Test @Timeout(20) void twoDispatchersCannotClaimTheSameRequestAndPgCallsHoldNoRowLocks() throws Exception {
        enqueue("concurrent").andExpect(status().isAccepted());
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            transactions.executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM reservation WHERE id=? FOR UPDATE NOWAIT", Long.class, reservationId);
                jdbc.queryForObject("SELECT id FROM payment WHERE id=? FOR UPDATE NOWAIT", Long.class, paymentId);
                jdbc.queryForObject("SELECT request_id FROM reservation_refund_outbox WHERE request_id=(SELECT id FROM reservation_refund_request WHERE payment_id=?) FOR UPDATE NOWAIT", Long.class, paymentId);
            });
            entered.countDown(); assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(toss).cancel(anyString(),any(),anyString(),anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(this::poll);
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try { executor.submit(this::poll).get(5,TimeUnit.SECONDS); } finally { finish.countDown(); }
            first.get(5,TimeUnit.SECONDS);
        }
        assertThat(toss.cancelKeys()).hasSize(1);
        assertThat(toss.cancelExecutions()).isEqualTo(1);
    }

    @Test @Timeout(20) void concurrentSameKeyCreatesExactlyOneIntentAndOneOutbox() throws Exception {
        try (var executor=Executors.newFixedThreadPool(4)) {
            CountDownLatch start=new CountDownLatch(1);
            var futures=java.util.stream.IntStream.range(0,8).mapToObj(i->executor.submit(()-> {
                start.await(); return enqueue("same-key").andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            var statuses=new java.util.ArrayList<Integer>();
            for(var future:futures) statuses.add(future.get(10,TimeUnit.SECONDS));
            assertThat(statuses).filteredOn(code->code==202).hasSize(1);
            assertThat(statuses).allMatch(code->code==200 || code==202);
        }
        assertThat(count("reservation_refund_request")).isEqualTo(1);
        assertThat(count("reservation_refund_outbox")).isEqualTo(1);
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test void staleWorkerCannotAcknowledgeOrRescheduleANewerLease() throws Exception {
        enqueue("fence").andExpect(status().isAccepted());
        Long id=jdbc.queryForObject("SELECT id FROM reservation_refund_request WHERE payment_id=?",Long.class,paymentId);
        var first=outbox.claim(id,NOW).orElseThrow();
        var second=outbox.claim(id,NOW.plusSeconds(120)).orElseThrow();
        outbox.delivered(first,NOW.plusSeconds(120)); outbox.retry(first,NOW.plusSeconds(120));
        assertThat(outboxStatus()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM reservation_refund_outbox WHERE request_id=?",Integer.class,id)).isEqualTo(2);
        outbox.retry(second,NOW.plusSeconds(120));
        clock.set(NOW.plusMinutes(8)); poll();
        assertThat(paymentState()).isEqualTo("REFUNDED");
        assertThat(toss.cancelExecutions()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints={14,15})
    void interruptedCommonRequestKeepsTheExistingFifteenDayRetryBoundary(int days) throws Exception {
        enqueue("old-common").andExpect(status().isAccepted());
        jdbc.update("INSERT INTO payment_refund(payment_id,idempotency_key,requested_amount,cancel_reason,refund_amount,status,requested_at) VALUES (?,'old-common',10000,?,10000,'REQUESTED',?)",paymentId,REASON,NOW);
        clock.set(NOW.plusDays(days)); poll();
        assertThat(requestStatus()).isEqualTo(days==14?"COMPLETED":"FAILED");
        assertThat(frozen()).isFalse();
        assertThat(toss.cancelKeys()).hasSize(days==14?1:0);
    }

    private void poll() { dispatcher.poll(); }
    private String path() { return "/api/user/payments/"+paymentId+"/unconfirmed-refund"; }
    private ResultActions enqueue(String key) throws Exception {
        return mvc.perform(post(path()).header("X-Member-Id", customerId).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"amount\":1}"));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private boolean frozen() { return jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id=?",Boolean.class,reservationId); }
    private String state() { return jdbc.queryForObject("SELECT status FROM reservation WHERE id=?",String.class,reservationId); }
    private String paymentState() { return jdbc.queryForObject("SELECT status FROM payment WHERE id=?",String.class,paymentId); }
    private String requestStatus() { return jdbc.queryForObject("SELECT status FROM reservation_refund_request WHERE payment_id=? ORDER BY id DESC LIMIT 1",String.class,paymentId); }
    private String outboxStatus() { return jdbc.queryForObject("SELECT status FROM reservation_refund_outbox WHERE request_id=(SELECT max(id) FROM reservation_refund_request WHERE payment_id=?)",String.class,paymentId); }
}
