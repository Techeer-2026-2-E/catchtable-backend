package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.payment.client.TossPaymentsException;
import com.catchtable.payment.client.TossPaymentsUnknownResultException;
import com.catchtable.payment.service.PaymentRefundService;
import com.catchtable.payment.service.PaymentReconcileService;
import com.catchtable.reservation.dto.OwnerReservationUpdateRequest;
import com.catchtable.store.repository.StoreTableRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReservationRefundExecutionTest.ClockFixture.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationRefundExecutionTest {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    static final OffsetDateTime START = NOW.plusHours(6);
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRefundExecutionService executions;
    @Autowired ReservationService customers;
    @Autowired OwnerReservationService owners;
    @Autowired PaymentReconcileService reconciler;
    @Autowired StoreTableRepository tables;
    @Autowired SseEmitterRepository emitters;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @MockBean TossPaymentsClient toss;
    @SpyBean PaymentRefundService common;
    @SpyBean ReservationRefundSettlementService settlement;
    PaymentTestData data;
    Long memberId, ownerId, foreignId, storeId, id, paymentId;
    SseEmitter emitter;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        ownerId = data.member("OWNER");
        foreignId = data.member("CUSTOMER");
        storeId = data.store(ownerId);
        emitter = mock(SseEmitter.class);
        emitters.save(memberId, emitter);
    }

    @AfterEach
    void cleanUp() {
        emitters.delete(memberId, emitter);
        data.deleteStore(storeId, List.of(memberId, ownerId, foreignId));
        clock.set(NOW);
    }

    @ParameterizedTest
    @CsvSource({"FLEXIBLE,1,0,10000", "FLEXIBLE,0,0,5000", "STRICT,0,0,0",
            "FLEXIBLE,0,4000,1000", "FLEXIBLE,0,6000,0"})
    void omittedAmountRefundUsesRemainingPolicyAndKeepsReservation(String policy, int days, long prior, long expected)
            throws Exception {
        paid(policy, days, prior);
        succeeds(prior + expected);
        var result = executions.refund(memberId, paymentId, "money", "고객 요청", null);
        assertThat(result.refundAmount()).isEqualTo(expected);
        assertThat(result.totalRefundedAmount()).isEqualTo(prior + expected);
        assertThat(executions.refund(memberId, paymentId, "money", "고객 요청", null)).isEqualTo(result);
        assertPreserved();
        assertThat(snapshot().get("requested_amount")).isNull();
        assertThat(snapshot().get("policy_refund_limit")).isEqualTo(days == 1 ? 10000L : policy.equals("STRICT") ? 0L : 5000L);
        if (expected == 0) verifyNoInteractions(toss);
        else {
            verify(toss, times(1)).cancel(anyString(), eq(expected), eq("고객 요청"), eq("money"));
            assertThat(jdbc.queryForObject("SELECT requested_amount FROM payment_refund WHERE payment_id = ?",
                    Long.class, paymentId)).isNull();
        }
    }

    @Test
    void explicitPartialAmountAndWonFloorUseTheOriginalSnapshot() {
        paid("FLEXIBLE", 0, 0);
        jdbc.update("UPDATE reservation SET deposit_amount_snapshot = 10001 WHERE id = ?", id);
        jdbc.update("UPDATE payment SET amount = 10001 WHERE id = ?", paymentId);
        when(toss.cancel(anyString(), any(), anyString(), anyString())).thenAnswer(call -> response(10001, 1000));
        var result = executions.refund(memberId, paymentId, "partial", "일부 환불", 1000L);
        assertThat(result.refundAmount()).isEqualTo(1000);
        assertThat(snapshot().get("policy_refund_limit")).isEqualTo(5000L);
        assertThat(snapshot().get("requested_amount")).isEqualTo(1000L);
        assertCode(() -> executions.refund(memberId, paymentId, "over", "초과", 4001L), ErrorCode.REFUND_AMOUNT_EXCEEDED);
        assertThat(requestCount()).isEqualTo(1);
        assertPreserved();
    }

    @ParameterizedTest
    @CsvSource({"false,FLEXIBLE,1,0,10000", "false,FLEXIBLE,0,1000,4000", "false,STRICT,0,0,0",
            "true,STRICT,-1,2000,8000"})
    void cancellationRefundsAllRemainingEntitlementThenReleasesOnce(boolean owner, String policy, int days,
                                                                    long prior, long expected) throws Exception {
        paid(policy, days, prior);
        succeeds(prior + expected);
        cancel(owner, "cancel");
        cancel(owner, "cancel");
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancellation_actor FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo(owner ? "OWNER" : "CUSTOMER");
        assertThat(available()).isEqualTo(1);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        if (expected > 0) verify(toss, times(1)).cancel(anyString(), eq(expected), anyString(), eq("cancel"));
        else verifyNoInteractions(toss);
    }

    @Test
    void paymentHttpUsesCurrentResultDtoAndCancellationStillNeedsItsOwnIntent() throws Exception {
        paid("FLEXIBLE", 0, 0);
        succeeds(5000);
        mvc.perform(post("/api/user/payments/{id}/cancel", paymentId).header("X-Member-Id", memberId)
                        .header("Idempotency-Key", "http").contentType("application/json")
                        .content("{\"cancelReason\":\"고객 요청\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("PARTIAL_REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.refundAmount").value(5000));
        assertPreserved();
        mvc.perform(post("/api/user/reservations/{id}/cancel", id).header("X-Member-Id", memberId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
    }

    @Test
    void bodylessCustomerCancellationRetainsFreeContractAndRequiresKeyForPositiveRefund() throws Exception {
        paid("FLEXIBLE", 0, 0);
        mvc.perform(post("/api/user/reservations/{id}/cancel", id).header("X-Member-Id", memberId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_NOT_COMPLETED"));
        assertThat(requestCount()).isZero();
        assertPreserved();
        succeeds(5000);
        mvc.perform(post("/api/user/reservations/{id}/cancel", id).header("X-Member-Id", memberId)
                        .header("Idempotency-Key", "customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void freeCustomerCancellationStillAcceptsNoBodyAndNoKey() throws Exception {
        paid("FLEXIBLE", 0, 0);
        jdbc.update("DELETE FROM payment WHERE id = ?", paymentId);
        jdbc.update("UPDATE reservation SET deposit_amount_snapshot = 0, refund_policy_snapshot = null, hold_expires_at = null WHERE id = ?", id);
        mvc.perform(post("/api/user/reservations/{id}/cancel", id).header("X-Member-Id", memberId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(requestCount()).isZero();
        verifyNoInteractions(toss);
    }

    @Test
    void ownerHttpKeepsMockIdentityAndReasonWhilePositiveRefundNeedsAKey() throws Exception {
        paid("STRICT", -1, 0);
        // 기존 점주 컨트롤러의 고정 식별자(2)를 실제 DB 소유권 검사까지 통과시킨다.
        boolean inserted = jdbc.queryForObject("SELECT count(*) FROM member WHERE id = 2", Integer.class) == 0;
        if (inserted) jdbc.update("INSERT INTO member(id, name, phone, user_type) OVERRIDING SYSTEM VALUE VALUES (2, 'mock', '000', 'OWNER')");
        jdbc.update("UPDATE store SET owner_id = 2 WHERE id = ?", storeId);
        try {
            mvc.perform(post("/api/owner/reservations/{id}/cancel", id).contentType("application/json")
                            .content("{\"reason\":\"매장 사정\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_NOT_COMPLETED"));
            mvc.perform(post("/api/owner/reservations/{id}/cancel", id).header("Idempotency-Key", "owner")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());
            succeeds(10000);
            mvc.perform(post("/api/owner/reservations/{id}/cancel", id).header("X-Member-Id", foreignId)
                            .header("Idempotency-Key", "owner").contentType("application/json")
                            .content("{\"reason\":\"매장 사정\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cancellationActor").value("OWNER"));
        } finally {
            jdbc.update("UPDATE store SET owner_id = ? WHERE id = ?", ownerId, storeId);
            if (inserted) jdbc.update("DELETE FROM member WHERE id = 2");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "role", "missing", "amount", "policy", "entered", "started", "key", "reason", "refunded", "mismatch", "binding"})
    void invalidRequestsNeverCreateTheFirstValidSnapshotOrCallPg(String invalid) {
        paid("FLEXIBLE", 0, 0);
        Long caller = memberId;
        Long target = paymentId;
        String key = "invalid", reason = "사유";
        Long amount = null;
        ErrorCode expected = ErrorCode.INVALID_INPUT;
        switch (invalid) {
            case "foreign" -> { caller = foreignId; expected = ErrorCode.PAYMENT_NOT_FOUND; }
            case "role" -> { caller = ownerId; expected = ErrorCode.FORBIDDEN_MEMBER_TYPE; }
            case "missing" -> { target = -1L; expected = ErrorCode.PAYMENT_NOT_FOUND; }
            case "amount" -> { amount = 5001L; expected = ErrorCode.REFUND_AMOUNT_EXCEEDED; }
            case "policy" -> { jdbc.update("UPDATE reservation SET deposit_amount_snapshot = null, refund_policy_snapshot = null WHERE id = ?", id); expected = ErrorCode.REFUND_DATA_NOT_READY; }
            case "refunded" -> { jdbc.update("UPDATE payment SET status = 'REFUNDED', refunded_amount = amount WHERE id = ?", paymentId); expected = ErrorCode.PAYMENT_NOT_REFUNDABLE; }
            case "mismatch" -> { jdbc.update("UPDATE payment SET amount = amount + 1 WHERE id = ?", paymentId); expected = ErrorCode.REFUND_DATA_NOT_READY; }
            case "binding" -> {
                data.protect(id, paymentId, data.paymentKey(paymentId), NOW.minusDays(2));
                jdbc.update("UPDATE reservation SET approval_order_id = 'different' WHERE id = ?", id);
                expected = ErrorCode.REFUND_DATA_NOT_READY;
            }
            case "entered" -> { jdbc.update("UPDATE reservation SET checked_in_at = ? WHERE id = ?", NOW, id); expected = ErrorCode.RESERVATION_NOT_CANCELLABLE; }
            case "started" -> { clock.set(START); expected = ErrorCode.RESERVATION_NOT_CANCELLABLE; }
            case "key" -> key = " ";
            case "reason" -> reason = " ";
        }
        Long finalCaller = caller, finalTarget = target, finalAmount = amount;
        String finalKey = key, finalReason = reason;
        assertCode(() -> executions.refund(finalCaller, finalTarget, finalKey, finalReason, finalAmount), expected);
        assertThat(requestCount()).isZero();
        assertThat(fenced()).isFalse();
        verifyNoInteractions(toss);
    }

    @Test
    void malformedPaymentHttpRequestsRetainAuthenticationAndValidationContract() throws Exception {
        paid("FLEXIBLE", 0, 0);
        mvc.perform(post("/api/user/payments/{id}/cancel", paymentId).header("Idempotency-Key", "k")
                        .contentType("application/json").content("{\"cancelReason\":\"사유\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/user/payments/{id}/cancel", paymentId).header("X-Member-Id", memberId)
                        .contentType("application/json").content("{\"cancelReason\":\"사유\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/user/payments/{id}/cancel", paymentId).header("X-Member-Id", memberId)
                        .header("Idempotency-Key", "k").contentType("application/json")
                        .content("{\"cancelReason\":\"사유\",\"cancelAmount\":0}"))
                .andExpect(status().isBadRequest());
        assertThat(requestCount()).isZero();
        verifyNoInteractions(toss);
    }

    @Test
    void definiteFailureReleasesTheFenceAndSameFailedKeyDoesNotCallPgAgain() {
        paid("FLEXIBLE", 0, 0);
        when(toss.cancel(anyString(), any(), anyString(), anyString()))
                .thenThrow(new TossPaymentsException(400, "REJECTED", "거절"));
        assertCode(() -> cancel(false, "failure"), ErrorCode.PAYMENT_REFUND_FAILED);
        assertThat(fenced()).isFalse();
        assertThat(snapshot().get("status")).isEqualTo("FAILED");
        assertCode(() -> cancel(false, "failure"), ErrorCode.PAYMENT_REFUND_FAILED);
        owners.confirmVisit(ownerId, id);
        assertThat(jdbc.queryForObject("SELECT checked_in_at FROM reservation WHERE id = ?", OffsetDateTime.class, id))
                .isNotNull();
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
        assertPreserved();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownCancellationRecoversThroughCommonReconcileWithSavedIntent(boolean owner) throws Exception {
        paid("FLEXIBLE", 0, 1000);
        when(toss.cancel(anyString(), any(), anyString(), anyString()))
                .thenThrow(new TossPaymentsUnknownResultException("유실", null));
        assertCode(() -> cancel(owner, "unknown"), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        var initial = basis();
        assertThat(fenced()).isTrue();
        assertAllMutationsBlocked();
        clock.set(NOW.plusDays(1));
        when(toss.getByPaymentKey(data.paymentKey(paymentId))).thenAnswer(call -> response(10000, owner ? 10000 : 5000));
        reconciler.reconcile(paymentId);
        reconciler.reconcile(paymentId);
        cancel(owner, "unknown");
        assertThat(basis()).isEqualTo(initial);
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(fenced()).isFalse();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
        verify(toss, times(1)).getByPaymentKey(anyString());
    }

    @Test
    void unknownMoneyOnlyDoesNotBecomeCancellationOnRecoveryAndDoesNotExpireLocally() {
        paid("FLEXIBLE", 0, 0);
        when(toss.cancel(anyString(), any(), anyString(), anyString()))
                .thenThrow(new TossPaymentsUnknownResultException("유실", null));
        assertCode(() -> executions.refund(memberId, paymentId, "unknown", "사유", 1000L), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        clock.set(NOW.plusDays(100));
        assertAllMutationsBlocked();
        when(toss.getByPaymentKey(data.paymentKey(paymentId))).thenAnswer(call -> response(10000, 1000));
        reconciler.reconcile(paymentId);
        assertThat(fenced()).isFalse();
        assertPreserved();
    }

    @Test
    void commonReconcileRetriesAnUnappliedRefundWithTheSavedAmountAndCancellationIntent() throws Exception {
        clock.set(NOW.minusDays(1));
        paid("FLEXIBLE", 0, 0);
        when(toss.cancel(anyString(), any(), anyString(), anyString()))
                .thenThrow(new TossPaymentsUnknownResultException("유실", null));
        assertCode(() -> cancel(false, "reconcile-retry"), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        var initial = basis();
        var commonInitial = jdbc.queryForMap("SELECT requested_at, requested_amount, idempotency_key, refund_amount FROM payment_refund WHERE payment_id = ?", paymentId);
        clock.set(NOW);
        when(toss.getByPaymentKey(data.paymentKey(paymentId))).thenAnswer(call -> response(10000, 0));
        succeeds(10000);
        reconciler.reconcile(paymentId);
        assertThat(basis()).isEqualTo(initial);
        assertThat(jdbc.queryForMap("SELECT requested_at, requested_amount, idempotency_key, refund_amount FROM payment_refund WHERE payment_id = ?", paymentId)).isEqualTo(commonInitial);
        assertThat(state()).isEqualTo("CANCELLED");
        verify(toss, times(2)).cancel(anyString(), eq(10000L), eq("고객 요청"), eq("reconcile-retry"));
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void onlyTheExistingCommonVerifiedUnappliedExpiryCanReleaseAnUnknownFence() {
        paid("FLEXIBLE", 0, 0);
        when(toss.cancel(anyString(), any(), anyString(), anyString()))
                .thenThrow(new TossPaymentsUnknownResultException("유실", null));
        assertCode(() -> cancel(false, "expire"), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        clock.set(NOW.plusDays(16));
        assertThat(fenced()).isTrue();
        when(toss.getByPaymentKey(data.paymentKey(paymentId))).thenAnswer(call -> response(10000, 0));
        reconciler.reconcile(paymentId);
        assertThat(fenced()).isFalse();
        assertThat(snapshot().get("status")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment_refund WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
        assertPreserved();
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
    }

    @Test
    @Timeout(25)
    void networkCallHoldsNoLocksAndCompetingKeysCannotPassTheDurableFence() throws Exception {
        paid("FLEXIBLE", 0, 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(toss.cancel(anyString(), any(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM reservation WHERE id = ? FOR UPDATE NOWAIT", Long.class, id);
                jdbc.queryForObject("SELECT id FROM payment WHERE id = ? FOR UPDATE NOWAIT", Long.class, paymentId);
            });
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return response(10000, 5000);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> cancel(false, "first"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertAllMutationsBlocked();
                assertCode(() -> cancel(false, "second"), ErrorCode.REFUND_DATA_NOT_READY);
                assertCode(() -> executions.refund(memberId, paymentId, "other", "다른 환불", 1000L), ErrorCode.REFUND_DATA_NOT_READY);
                assertCode(() -> executions.refund(memberId, paymentId, "first", "다른 의도", null), ErrorCode.IDEMPOTENCY_CONFLICT);
                assertPreserved();
            } finally { release.countDown(); }
            running.get(10, TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(requestCount()).isEqualTo(1);
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
    }

    @Test
    @Timeout(25)
    void concurrentSameKeyUsesOnePgIdempotentOperationAndOneCancellationEvent() throws Exception {
        paid("FLEXIBLE", 0, 0);
        CountDownLatch both = new CountDownLatch(2);
        when(toss.cancel(anyString(), any(), anyString(), eq("same"))).thenAnswer(call -> {
            both.countDown();
            assertThat(both.await(10, TimeUnit.SECONDS)).isTrue();
            return response(10000, 5000);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> executions.cancelCustomer(memberId, id, "same"));
            var second = executor.submit(() -> executions.cancelCustomer(memberId, id, "same"));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(requestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_refund WHERE payment_id = ?", Integer.class, paymentId))
                .isEqualTo(1);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, paymentId)).isEqualTo(5000);
    }

    @Test
    void changedPayloadOrOmittedVersusExplicitAmountConflictsAfterCompletion() {
        paid("FLEXIBLE", 0, 0);
        succeeds(1000);
        var result = executions.refund(memberId, paymentId, "key", "사유", 1000L);
        assertCode(() -> executions.refund(memberId, paymentId, "key", "사유", null), ErrorCode.IDEMPOTENCY_CONFLICT);
        assertCode(() -> executions.refund(memberId, paymentId, "key", "다른 사유", 1000L), ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(executions.refund(memberId, paymentId, "key", "사유", 1000L)).isEqualTo(result);
        assertThat(requestCount()).isEqualTo(1);
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "UNKNOWN"})
    void legacyCommonRefundsAlsoBlockEveryMutationAndTheBulkCompletionQuery(String status) {
        paid("FLEXIBLE", 0, 0);
        data.inFlightRefund(paymentId, "legacy", 1000, status, NOW.minusDays(1));
        assertThat(fenced()).isFalse();
        assertAllMutationsBlocked();
        assertThat(requestCount()).isZero();
        verifyNoInteractions(toss);
    }

    @Test
    void commonCompletionBeforeReservationSettlementIsRecoveredWithoutAnotherPgCall() throws Exception {
        paid("FLEXIBLE", 0, 0);
        succeeds(5000);
        ReservationRefundSettlementService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(settlement);
        doThrow(new IllegalStateException("정산 직전 중단")).when(target).settle(any(), anyList(), any());
        assertThatThrownBy(() -> cancel(false, "crash")).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(fenced()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_refund WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo("COMPLETED");
        doCallRealMethod().when(target).settle(any(), anyList(), any());
        clock.set(NOW.plusDays(1));
        reconciler.reconcile(paymentId);
        cancel(false, "crash");
        assertThat(state()).isEqualTo("CANCELLED");
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
        verify(toss, never()).getByPaymentKey(anyString());
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void preparationCommitBeforeCommonRequestAlreadyFencesAndCanResumeWithTheSameSnapshot() {
        clock.set(NOW.minusDays(1));
        paid("FLEXIBLE", 0, 0);
        doThrow(new IllegalStateException("공통 요청 저장 전 중단")).when(common)
                .refund(eq(memberId), eq(paymentId), eq("gap"), anyString(), isNull(), anyLong());
        assertThatThrownBy(() -> cancel(false, "gap")).isInstanceOf(IllegalStateException.class);
        var initial = basis();
        assertThat(fenced()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_refund WHERE payment_id = ?", Integer.class, paymentId)).isZero();
        assertAllMutationsBlocked();
        doCallRealMethod().when(common).refund(eq(memberId), eq(paymentId), eq("gap"), anyString(), isNull(), anyLong());
        succeeds(10000);
        cancel(false, "gap");
        assertThat(basis()).isEqualTo(initial);
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(fenced()).isFalse();
    }

    @Test
    void completedOwnerCancellationReplayCannotRefundAgainOrAffectTheReturnedSlot() throws Exception {
        paid("STRICT", 0, 0);
        succeeds(10000);
        cancel(true, "owner-completed");
        Long next = data.hold(foreignId, storeId, 0);
        jdbc.update("UPDATE reservation SET requested_at = ?, hold_expires_at = ? WHERE id = ?", NOW, NOW.plusMinutes(5), next);
        cancel(true, "owner-completed");
        assertCode(() -> executions.cancelOwner(ownerId, id, "owner-completed", "다른 사유"), ErrorCode.IDEMPOTENCY_CONFLICT);
        reconciler.reconcile(paymentId);
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, next)).isEqualTo("PENDING_PAYMENT");
        assertThat(state()).isEqualTo("CANCELLED");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(toss, times(1)).cancel(anyString(), any(), anyString(), anyString());
    }

    void paid(String policy, int days, long prior) {
        id = data.hold(memberId, storeId, 0);
        var start = START.plusDays(days);
        jdbc.update("""
                UPDATE reservation SET status = 'CONFIRMED', confirmed_at = ?, deposit_amount_snapshot = 10000,
                    refund_policy_snapshot = ?, reservation_start_at = ?, reservation_end_at = ? WHERE id = ?
                """, NOW.minusDays(2), policy, start, start.plusHours(1), id);
        paymentId = data.payment(memberId, id, prior == 0 ? "DONE" : "PARTIAL_REFUNDED", prior);
    }

    void succeeds(long total) {
        doAnswer(call -> response(10000, total)).when(toss).cancel(anyString(), any(), anyString(), anyString());
    }

    TossPaymentResponse response(long paid, long total) {
        return new TossPaymentResponse(data.paymentKey(paymentId), data.orderId(paymentId), "예약금",
                total == 0 ? "DONE" : total == paid ? "CANCELED" : "PARTIAL_CANCELED", "카드", paid, paid - total,
                NOW.minusDays(2), new TossPaymentResponse.Receipt("https://receipt"), null);
    }

    void cancel(boolean owner, String key) {
        if (owner) executions.cancelOwner(ownerId, id, key, "매장 사정");
        else executions.cancelCustomer(memberId, id, key);
    }

    void assertAllMutationsBlocked() {
        assertCode(() -> owners.updateReservation(ownerId, id, new OwnerReservationUpdateRequest(START.plusDays(2), null)), ErrorCode.REFUND_DATA_NOT_READY);
        assertCode(() -> owners.updateReservation(ownerId, id, new OwnerReservationUpdateRequest(null, 3)), ErrorCode.REFUND_DATA_NOT_READY);
        assertCode(() -> owners.confirmVisit(ownerId, id), ErrorCode.REFUND_DATA_NOT_READY);
        assertCode(() -> owners.markNoShow(ownerId, id, "미방문"), ErrorCode.REFUND_DATA_NOT_READY);
        assertCode(() -> customers.cancelReservation(memberId, id), ErrorCode.REFUND_DATA_NOT_READY);
        assertCode(() -> owners.cancelReservation(ownerId, id, "다른 취소"), ErrorCode.REFUND_DATA_NOT_READY);
        // 기존/외부 원장에 입장 기록이 이미 있어도 native 일괄 완료가 환불 보호를 우회하지 않는다.
        var before = jdbc.queryForMap("SELECT checked_in_at, reservation_end_at FROM reservation WHERE id = ?", id);
        jdbc.update("UPDATE reservation SET checked_in_at = ?, reservation_end_at = reservation_start_at + interval '1 hour' WHERE id = ?", NOW.minusDays(2), id);
        var afterEnd = jdbc.queryForObject("SELECT reservation_end_at FROM reservation WHERE id = ?", OffsetDateTime.class, id).plusDays(1);
        clock.set(afterEnd);
        customers.completeFinishedReservations();
        assertThat(state()).isEqualTo("CONFIRMED");
        jdbc.update("UPDATE reservation SET checked_in_at = ?, reservation_end_at = ? WHERE id = ?",
                before.get("checked_in_at"), before.get("reservation_end_at"), id);
    }

    static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    void assertPreserved() {
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(available()).isZero();
        verifyNoInteractions(emitter);
    }

    long available() {
        var start = jdbc.queryForObject("SELECT reservation_start_at FROM reservation WHERE id = ?", OffsetDateTime.class, id);
        return tables.countAvailable(storeId, 2, start, start.plusHours(1), NOW);
    }
    String state() { return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id); }
    boolean fenced() { return jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id = ?", Boolean.class, id); }
    int requestCount() { return jdbc.queryForObject("SELECT count(*) FROM reservation_refund_request WHERE reservation_id = ?", Integer.class, id); }
    Map<String, Object> snapshot() { return jdbc.queryForMap("SELECT * FROM reservation_refund_request WHERE reservation_id = ?", id); }
    Map<String, Object> basis() {
        return jdbc.queryForMap("SELECT requested_at, reservation_start_at, policy_refund_limit, requested_amount, refund_amount FROM reservation_refund_request WHERE reservation_id = ?", id);
    }

    @TestConfiguration
    static class ClockFixture {
        @Bean @Primary MutableClock refundClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<OffsetDateTime> current = new AtomicReference<>(NOW);
        void set(OffsetDateTime now) { current.set(now); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get().toInstant(); }
    }
}
