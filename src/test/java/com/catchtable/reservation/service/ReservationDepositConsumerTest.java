package com.catchtable.reservation.service;

import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.dto.PaymentPrepareResponse;
import com.catchtable.reservation.dto.ReservationHoldResponse;
import com.catchtable.reservation.entity.RefundPolicy;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 공개 생성부터 검증하되, 기능33의 실제 저장 어댑터 대신 테스트 전용 서버 원본을 사용한다. */
@SpringBootTest(properties = {"reservation.hold-expiry-interval-ms=3600000", "reservation.completion-interval-ms=3600000"})
@AutoConfigureMockMvc
@Import({ReservationDepositConsumerTest.ClockFixture.class, ReservationDepositConsumerTest.SourceFixture.class,
        FakeTossPaymentsClient.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationDepositConsumerTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    static final OffsetDateTime START = NOW.plusDays(3);
    static final long AMOUNT = 10_001;
    static final ReservationDepositTermsReader.Terms FREE = new ReservationDepositTermsReader.Terms(false, 0, null);
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired TestTermsReader source;
    @Autowired TransactionTemplate transactions;
    @Autowired ReservationService reservations;
    @Autowired SseEmitterRepository emitters;
    @SpyBean ReservationRepository repository;
    @SpyBean FakeTossPaymentsClient toss;
    PaymentTestData data;
    Long memberId, foreignId, storeId;
    final List<Long> stores = new ArrayList<>();
    final List<Long> mockMembers = new ArrayList<>();
    SseEmitter emitter, directEmitter;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        toss.reset();
        clearInvocations(toss);
        source.terms.clear();
        source.onRead = store -> {};
        // 기존 API의 목 고객1·점주2를 보존하며, 테스트 고객은 별도 서버 회원으로 만든다.
        for (long id = 1; id <= 2; id++) {
            jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('member', 'id'))", Long.class);
            if (jdbc.update("""
                    INSERT INTO member (id, email, password, name, phone, user_type)
                    OVERRIDING SYSTEM VALUE
                    VALUES (?, ?, 'test', 'test', '010-0000-0000', ?) ON CONFLICT (id) DO NOTHING
                    """, id, "deposit-mock-" + id + "@example.com", id == 1 ? "CUSTOMER" : "OWNER") == 1) {
                mockMembers.add(id);
            }
        }
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        foreignId = data.member("CUSTOMER");
        storeId = newStore();
        source.terms.put(storeId, () -> paid(AMOUNT, RefundPolicy.STANDARD));
        emitter = mock(SseEmitter.class);
        emitters.save(memberId, emitter);
        directEmitter = mock(SseEmitter.class);
        emitters.save(1L, directEmitter);
    }

    @AfterEach
    void cleanUp() {
        source.onRead = store -> {};
        emitters.delete(memberId, emitter);
        emitters.delete(1L, directEmitter);
        for (Long id : stores) {
            jdbc.update("DELETE FROM reservation_refund_request WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", id);
            jdbc.update("DELETE FROM business_hour WHERE store_id = ?", id);
            data.deleteStore(id, List.of());
        }
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, foreignId);
        mockMembers.forEach(id -> jdbc.update("DELETE FROM member WHERE id = ?", id));
        stores.clear();
        mockMembers.clear();
        source.terms.clear();
        clock.set(NOW);
    }

    @ParameterizedTest
    @EnumSource(RefundPolicy.class)
    void publicPaidHoldSavesOnlyTrustedOriginalsAtomicallyWithoutCreatingAnOrder(RefundPolicy policy) throws Exception {
        source.terms.put(storeId, () -> paid(AMOUNT, policy));
        var hold = hold(START, 4);
        var row = row(hold.holdId());
        assertThat(row).containsEntry("member_id", memberId).containsEntry("party_size", 4)
                .containsEntry("deposit_amount_snapshot", AMOUNT).containsEntry("refund_policy_snapshot", policy.name())
                .containsEntry("status", "PENDING_PAYMENT").containsEntry("confirmed_at", null);
        assertThat(((Timestamp) row.get("requested_at")).toInstant()).isEqualTo(NOW.toInstant());
        assertThat(((Timestamp) row.get("hold_expires_at")).toInstant()).isEqualTo(NOW.plusMinutes(5).toInstant());
        assertThat(hold.holdId()).isEqualTo(hold.reservationId());
        assertThat(hold.expiresAt()).isAtSameInstantAs(NOW.plusMinutes(5));
        assertThat(hold.remainingSeconds()).isEqualTo(300);
        assertThat(hold.reservationEndAt()).isAtSameInstantAs(START.plusHours(1));
        assertThat(reservationCount()).isEqualTo(1);
        assertNoPaymentOrNotification();
    }

    @Test
    void amountIsPerBookingAndDoesNotOverflowWhenPartySizeIsFour() throws Exception {
        source.terms.put(storeId, () -> paid(Long.MAX_VALUE, RefundPolicy.FLEXIBLE));
        var hold = hold(START, 4);
        assertThat(row(hold.holdId())).containsEntry("deposit_amount_snapshot", Long.MAX_VALUE);
        assertThat(prepare(hold.holdId(), 201).amount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void directPaidCreateRejectsBeforeExpiryCleanupOrAnyOtherMutation() throws Exception {
        mvc.perform(post("/api/user/reservations").contentType("application/json")
                        .content(body(storeId, START, 2).replace("\"depositEnabled\":true", "\"depositEnabled\":false")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_PAYMENT_REQUIRED"))
                .andExpect(jsonPath("$.message").value(containsString("선점 생성 → 결제 준비 → 승인")));
        assertThat(reservationCount()).isZero();
        var hold = hold(START, 2);
        clock.set(NOW.plusMinutes(5));
        var before = row(hold.holdId());
        clearInvocations(repository);
        create(storeId, START, 2, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_PAYMENT_REQUIRED"));
        verify(repository, never()).expireHolds(any(), any());
        assertThat(row(hold.holdId())).isEqualTo(before);
        assertThat(reservationCount()).isEqualTo(1);
        assertNoPaymentOrNotification();
    }

    @Test
    void explicitFreeKeepsImmediateConfirmationAndLegacyHoldWithNoPaidSnapshot() throws Exception {
        source.terms.put(storeId, () -> FREE);
        create(storeId, START, 2, false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(jdbc.queryForMap("SELECT member_id, deposit_amount_snapshot, refund_policy_snapshot FROM reservation WHERE store_id = ?", storeId))
                .containsEntry("member_id", 1L).containsEntry("deposit_amount_snapshot", 0L)
                .containsEntry("refund_policy_snapshot", null);
        var hold = hold(START.plusHours(2), 2);
        assertThat(row(hold.holdId())).containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("deposit_amount_snapshot", null).containsEntry("refund_policy_snapshot", null);
        prepareRequest(hold.holdId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertNoPaymentOrNotification();
    }

    @Test
    void sourceIsSelectedBySavedStoreIdentityRatherThanClientMoneyOrEnabledFlag() throws Exception {
        Long freeStore = newStore();
        source.terms.put(freeStore, () -> FREE);
        create(storeId, START, 2, false).andExpect(status().isConflict());
        create(freeStore, START, 2, false).andExpect(status().isCreated());
        var paid = hold(START, 2);
        assertThat(row(paid.holdId())).containsEntry("deposit_amount_snapshot", AMOUNT);
        create(freeStore, START.plusHours(2), 2, true).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT deposit_amount_snapshot FROM reservation WHERE store_id = ? AND status = 'PENDING_PAYMENT'", Long.class, freeStore)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "failure", "paidZero", "paidNegative", "paidPolicyMissing", "freePositive", "freeNegative", "freePolicy"})
    void installedSourceMissingFailingOrInconsistentTermsNeverFallBackToFree(String invalid) throws Exception {
        source.terms.put(storeId, () -> switch (invalid) {
            case "missing" -> null;
            case "failure" -> throw new IllegalStateException("test settings source failure");
            case "paidZero" -> paid(0, RefundPolicy.FLEXIBLE);
            case "paidNegative" -> paid(-1, RefundPolicy.FLEXIBLE);
            case "paidPolicyMissing" -> paid(AMOUNT, null);
            case "freePositive" -> new ReservationDepositTermsReader.Terms(false, AMOUNT, null);
            case "freeNegative" -> new ReservationDepositTermsReader.Terms(false, -1, null);
            case "freePolicy" -> new ReservationDepositTermsReader.Terms(false, 0, RefundPolicy.STRICT);
            default -> throw new AssertionError(invalid);
        });
        for (boolean hold : List.of(false, true)) {
            create(storeId, START, 2, hold).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESERVATION_DEPOSIT_TERMS_UNAVAILABLE"));
        }
        assertThat(reservationCount()).isZero();
        assertNoPaymentOrNotification();
    }

    @Test
    void validationNotFoundAndHoldAuthenticationStillApply() throws Exception {
        create(storeId, START, 0, false).andExpect(status().isBadRequest());
        create(storeId, NOW, 2, false).andExpect(status().isBadRequest());
        create(Long.MAX_VALUE, START, 2, false).andExpect(status().isNotFound());
        mvc.perform(post("/api/user/reservations/holds").contentType("application/json")
                .content(body(storeId, START, 2))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/user/reservations/holds").header("X-Member-Id", Long.MAX_VALUE)
                .contentType("application/json").content(body(storeId, START, 2))).andExpect(status().isNotFound());
        assertThat(reservationCount()).isZero();
        assertNoPaymentOrNotification();
    }

    @Test
    void paidHoldsRetainCapacityValidation() throws Exception {
        create(storeId, START, 5, true).andExpect(status().isConflict());
        assertThat(reservationCount()).isZero();
        assertNoPaymentOrNotification();
    }

    @Test
    @Timeout(20)
    void twoPublicPaidHoldsCompeteForTheSameLastTable() throws Exception {
        CountDownLatch read = new CountDownLatch(2), start = new CountDownLatch(1);
        source.onRead = store -> { read.countDown(); awaitLatch(start); };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> create(storeId, START, 2, true).andReturn().getResponse().getStatus());
            var second = workers.submit(() -> create(storeId, START, 2, true).andReturn().getResponse().getStatus());
            try {
                assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(reservationCount()).isEqualTo(1);
        assertNoPaymentOrNotification();
    }

    @Test
    @Timeout(20)
    void storeWriterWaitsUntilTermsAndAllocationCommitAndOnlyChangesFutureHolds() throws Exception {
        CountDownLatch read = new CountDownLatch(1), finish = new CountDownLatch(1);
        source.onRead = store -> { read.countDown(); awaitLatch(finish); };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var creating = workers.submit(() -> hold(START, 2));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            var changing = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.update("UPDATE store SET name = 'changed settings source' WHERE id = ?", storeId);
                source.terms.put(storeId, () -> paid(30_003, RefundPolicy.STRICT));
            }));
            try {
                awaitStoreWaiter();
                assertThat(changing.isDone()).isFalse();
            } finally {
                finish.countDown();
            }
            var first = creating.get(10, TimeUnit.SECONDS);
            changing.get(10, TimeUnit.SECONDS);
            assertThat(row(first.holdId())).containsEntry("deposit_amount_snapshot", AMOUNT)
                    .containsEntry("refund_policy_snapshot", "STANDARD");
            source.onRead = store -> {};
            var next = hold(START.plusHours(2), 2);
            assertThat(row(next.holdId())).containsEntry("deposit_amount_snapshot", 30_003L)
                    .containsEntry("refund_policy_snapshot", "STRICT");
        } finally {
            finish.countDown();
        }
    }

    @Test
    @Timeout(20)
    void waitingCreationReadsCommittedStoreAndTermsAndStartsItsClockAfterTheLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), commit = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var changing = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.update("UPDATE store SET reservation_duration_minutes = 90 WHERE id = ?", storeId);
                source.terms.put(storeId, () -> paid(20_002, RefundPolicy.FLEXIBLE));
                locked.countDown();
                awaitLatch(commit);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var creating = workers.submit(() -> hold(START, 2));
            try {
                awaitStoreWaiter();
                clock.set(NOW.plusMinutes(3));
            } finally {
                commit.countDown();
            }
            changing.get(10, TimeUnit.SECONDS);
            var hold = creating.get(10, TimeUnit.SECONDS);
            assertThat(hold.reservationEndAt()).isAtSameInstantAs(START.plusMinutes(90));
            assertThat(row(hold.holdId())).containsEntry("deposit_amount_snapshot", 20_002L)
                    .containsEntry("refund_policy_snapshot", "FLEXIBLE");
            assertThat(hold.expiresAt()).isAtSameInstantAs(NOW.plusMinutes(8));
        } finally {
            commit.countDown();
        }
    }

    @Test
    void readyPrepareReusesOriginalOrderAmountAndExpiryEvenAfterSettingsChange() throws Exception {
        var hold = hold(START, 4);
        var prepared = prepare(hold.holdId(), 201);
        var original = row(hold.holdId());
        var originalPayment = jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", prepared.paymentId());
        source.terms.put(storeId, () -> FREE);
        clock.set(NOW.plusMinutes(2));
        var repeated = prepare(hold.holdId(), 200);
        assertThat(repeated.orderId()).isEqualTo(prepared.orderId());
        assertThat(repeated.paymentId()).isEqualTo(prepared.paymentId());
        assertThat(repeated.amount()).isEqualTo(AMOUNT);
        assertThat(repeated.expiresAt()).isAtSameInstantAs(hold.expiresAt());
        assertThat(repeated.remainingSeconds()).isEqualTo(180);
        assertThat(row(hold.holdId())).isEqualTo(original);
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", prepared.paymentId())).isEqualTo(originalPayment);
        assertThat(historyCount()).isEqualTo(1);
        verifyNoInteractions(toss, emitter, directEmitter);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void publicPaidHoldPrepareKeepsTheExactOriginalFiveMinuteBoundary(long microseconds) throws Exception {
        var hold = hold(START, 2);
        clock.set(NOW.plusMinutes(5).plusNanos(microseconds * 1000));
        prepareRequest(hold.holdId()).andExpect(status().is(microseconds < 0 ? 201 : 409));
        assertThat(row(hold.holdId()).get("confirmed_at")).isNull();
        assertThat(((Timestamp) row(hold.holdId()).get("hold_expires_at")).toInstant()).isEqualTo(NOW.plusMinutes(5).toInstant());
        assertThat(historyCount()).isEqualTo(microseconds < 0 ? 1 : 0);
        verifyNoInteractions(toss, emitter);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void approvalMustStartBeforeTheOriginalDeadline(long microseconds) throws Exception {
        var hold = hold(START, 2);
        var prepared = prepare(hold.holdId(), 201);
        clock.set(NOW.plusMinutes(5).plusNanos(microseconds * 1000));
        confirm(prepared).andExpect(status().is(microseconds < 0 ? 200 : 409));
        assertThat(row(hold.holdId()).get("confirmed_at") != null).isEqualTo(microseconds < 0);
        if (microseconds >= 0) verifyNoInteractions(toss, emitter);
    }

    @Test
    @Timeout(20)
    void lateDoneConfirmsSamePublicHoldOnceAndStartedApprovalCannotPrepareOrReleaseAgain() throws Exception {
        var hold = hold(START, 2);
        var prepared = prepare(hold.holdId(), 201);
        CountDownLatch called = new CountDownLatch(1), finish = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            called.countDown();
            awaitLatch(finish);
            return call.callRealMethod();
        }).when(toss).confirm(anyString(), anyString(), anyLong(), anyString());
        doAnswer(call -> {
            try (var connection = jdbc.getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT status FROM reservation WHERE id = ?")) {
                statement.setLong(1, hold.holdId());
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("CONFIRMED");
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var approval = worker.submit(() -> confirm(prepared).andExpect(status().isOk()));
            try {
                assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
                clock.set(NOW.plusMinutes(6));
                assertThat(reservations.expireHolds()).isZero();
                prepareRequest(hold.holdId()).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PAYMENT_STATUS_CHECK_REQUIRED"));
                mvc.perform(delete("/api/user/reservations/holds/{id}", hold.holdId()).header("X-Member-Id", memberId))
                        .andExpect(status().isConflict());
                create(storeId, START, 2, true).andExpect(status().isConflict());
            } finally {
                finish.countDown();
            }
            approval.get(10, TimeUnit.SECONDS);
        }
        var confirmed = row(hold.holdId());
        confirm(prepared).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(row(hold.holdId())).isEqualTo(confirmed);
        assertThat(confirmed).containsEntry("table_id", hold.tableId()).containsEntry("status", "CONFIRMED");
        assertThat(((Timestamp) confirmed.get("reservation_start_at")).toInstant()).isEqualTo(START.toInstant());
        assertThat(((Timestamp) confirmed.get("hold_expires_at")).toInstant()).isEqualTo(hold.expiresAt().toInstant());
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(2);
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "TIMEOUT", "FAILED"})
    void publicFlowNeverConfirmsForNonDoneOutcomes(String outcome) throws Exception {
        var hold = hold(START, 2);
        var prepared = prepare(hold.holdId(), 201);
        if (outcome.equals("READY")) {
            doReturn(new TossPaymentResponse("consumer-key", prepared.orderId(), "예약금", "READY", null,
                    AMOUNT, AMOUNT, null, null, null)).when(toss).confirm(anyString(), anyString(), anyLong(), anyString());
        } else {
            toss.failNext(outcome.equals("TIMEOUT") ? FakeTossPaymentsClient.timeout() : FakeTossPaymentsClient.rejected());
        }
        confirm(prepared).andExpect(status().is(outcome.equals("FAILED") ? 200 : 409));
        clock.set(NOW.plusMinutes(6));
        reservations.expireHolds();
        assertThat(row(hold.holdId()).get("confirmed_at")).isNull();
        assertThat(row(hold.holdId()).get("status")).isEqualTo(outcome.equals("FAILED") ? "CANCELLED" : "PENDING_PAYMENT");
        prepareRequest(hold.holdId()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class, hold.holdId())).isEqualTo(1);
        verifyNoInteractions(emitter);
    }

    @Test
    void publicCreatePrepareConfirmChangePartialRefundAndCancelUseFirstSnapshotsAndCurrentKstDate() throws Exception {
        var hold = hold(START, 4);
        var prepared = prepare(hold.holdId(), 201);
        confirm(prepared).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        source.terms.put(storeId, () -> paid(30_003, RefundPolicy.STRICT));
        OffsetDateTime changedStart = OffsetDateTime.parse("2030-01-20T15:00:00Z"); // 서울 1월21일
        mvc.perform(patch("/api/owner/reservations/{id}", hold.holdId()).header("X-Member-Id", 2)
                .contentType("application/json").content("{\"reservationStartAt\":\"%s\",\"partySize\":3}".formatted(changedStart)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.partySize").value(3));
        assertOriginal(hold.holdId());
        preview(hold.holdId()).andExpect(jsonPath("$.policyType").value("STANDARD"))
                .andExpect(jsonPath("$.paidAmount").value(AMOUNT)).andExpect(jsonPath("$.refundRate").value(50))
                .andExpect(jsonPath("$.refundableAmount").value(5000));
        mvc.perform(post("/api/user/payments/{id}/cancel", prepared.paymentId()).header("X-Member-Id", memberId)
                .header("Idempotency-Key", "partial-first").contentType("application/json")
                .content("{\"cancelReason\":\"일부 환불\",\"cancelAmount\":1000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(1000));
        preview(hold.holdId()).andExpect(jsonPath("$.alreadyRefundedAmount").value(1000))
                .andExpect(jsonPath("$.refundableAmount").value(4000));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/user/reservations/{id}/cancel", hold.holdId()).header("X-Member-Id", memberId)
                    .header("Idempotency-Key", "consumer-cancel"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        var basis = jdbc.queryForMap("SELECT policy_refund_limit, refund_amount, requested_at FROM reservation_refund_request WHERE reservation_id = ? AND idempotency_key = 'consumer-cancel'", hold.holdId());
        assertThat(basis).containsEntry("policy_refund_limit", 5000L).containsEntry("refund_amount", 4000L);
        assertThat(((Timestamp) basis.get("requested_at")).toInstant()).isEqualTo(NOW.toInstant());
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, prepared.paymentId())).isEqualTo(5000);
        assertOriginal(hold.holdId());
        assertThat(toss.cancelExecutions()).isEqualTo(2);
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void ownerMockTwoCancelRequiresReasonAndRefundsTheOriginalFullAmount() throws Exception {
        var hold = hold(START, 2);
        var prepared = prepare(hold.holdId(), 201);
        confirm(prepared).andExpect(status().isOk());
        source.terms.put(storeId, () -> FREE);
        mvc.perform(post("/api/owner/reservations/{id}/cancel", hold.holdId()).header("Idempotency-Key", "owner-cancel")
                .contentType("application/json").content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/owner/reservations/{id}/cancel", hold.holdId()).header("X-Member-Id", foreignId)
                    .header("Idempotency-Key", "owner-cancel").contentType("application/json")
                    .content("{\"reason\":\"매장 사정\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        assertThat(row(hold.holdId())).containsEntry("cancellation_actor", "OWNER")
                .containsEntry("cancellation_reason", "매장 사정");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, prepared.paymentId())).isEqualTo(AMOUNT);
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        assertOriginal(hold.holdId());
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "UNKNOWN"})
    @Timeout(20)
    void publicPaidFlowRetainsTheRefundMutationFenceAndFirstValidRequestBasis(String state) throws Exception {
        var hold = hold(START, 2);
        var prepared = prepare(hold.holdId(), 201);
        confirm(prepared).andExpect(status().isOk());
        CountDownLatch called = new CountDownLatch(1), finish = new CountDownLatch(1);
        if (state.equals("UNKNOWN")) toss.failNext(FakeTossPaymentsClient.timeout());
        else doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            called.countDown();
            awaitLatch(finish);
            return call.callRealMethod();
        }).when(toss).cancel(anyString(), any(), anyString(), anyString());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var cancel = worker.submit(() -> mvc.perform(post("/api/user/reservations/{id}/cancel", hold.holdId())
                    .header("X-Member-Id", memberId).header("Idempotency-Key", "fenced-cancel"))
                    .andExpect(status().is(state.equals("UNKNOWN") ? 409 : 200)));
            try {
                if (state.equals("REQUESTED")) assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
                else cancel.get(10, TimeUnit.SECONDS);
                var basis = jdbc.queryForMap("SELECT requested_at, policy_refund_limit, refund_amount FROM reservation_refund_request WHERE reservation_id = ?", hold.holdId());
                assertThat(basis).containsEntry("policy_refund_limit", AMOUNT).containsEntry("refund_amount", AMOUNT);
                assertThat(jdbc.queryForObject("SELECT status FROM payment_refund WHERE payment_id = ?", String.class, prepared.paymentId())).isEqualTo(state);
                mvc.perform(patch("/api/owner/reservations/{id}", hold.holdId()).header("X-Member-Id", 2)
                        .contentType("application/json").content("{\"partySize\":3}"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_DATA_NOT_READY"));
                for (String action : List.of("enter", "no-show", "cancel")) {
                    mvc.perform(post("/api/owner/reservations/{id}/" + action, hold.holdId())
                            .contentType("application/json").content("{\"reason\":\"다른 요청\"}"))
                            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_DATA_NOT_READY"));
                }
                mvc.perform(post("/api/user/reservations/{id}/cancel", hold.holdId()).header("X-Member-Id", memberId))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_DATA_NOT_READY"));
                // 외부/기존 입장 기록이 있어도 일괄 완료가 보호를 우회하지 않는다.
                jdbc.update("UPDATE reservation SET checked_in_at = ? WHERE id = ?", NOW, hold.holdId());
                clock.set(START.plusHours(2));
                assertThat(reservations.completeFinishedReservations()).isZero();
                jdbc.update("UPDATE reservation SET checked_in_at = NULL WHERE id = ?", hold.holdId());
                clock.set(NOW.plusMinutes(1));
                assertThat(row(hold.holdId()).get("status")).isEqualTo("CONFIRMED");
                assertThat(jdbc.queryForMap("SELECT requested_at, policy_refund_limit, refund_amount FROM reservation_refund_request WHERE reservation_id = ?", hold.holdId())).isEqualTo(basis);
                assertOriginal(hold.holdId());
            } finally {
                finish.countDown();
            }
            cancel.get(10, TimeUnit.SECONDS);
        }
    }

    private Long newStore() {
        Long id = data.store(2L);
        stores.add(id);
        jdbc.update("INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time) SELECT ?, day, '00:00', '23:59' FROM generate_series(1, 7) day", id);
        return id;
    }

    private String body(Long id, OffsetDateTime start, int size) {
        // 클라이언트가 추가한 결제 원본·만료·회원·테이블 필드는 생성 DTO에 없다.
        return """
                {"storeId":%d,"reservationStartAt":"%s","partySize":%d,
                 "depositEnabled":true,"amount":1,"depositAmount":1,"refundPolicy":"STRICT",
                 "memberId":%d,"tableId":999999,"requestedAt":"2099-01-01T00:00:00Z","expiresAt":"2099-01-01T00:05:00Z"}
                """.formatted(id, start, size, foreignId);
    }

    private ResultActions create(Long id, OffsetDateTime start, int size, boolean hold) throws Exception {
        return mvc.perform(post("/api/user/reservations" + (hold ? "/holds" : ""))
                .header("X-Member-Id", memberId).contentType("application/json").content(body(id, start, size)));
    }
    private ReservationHoldResponse hold(OffsetDateTime start, int size) throws Exception {
        return json.readValue(create(storeId, start, size, true).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), ReservationHoldResponse.class);
    }
    private ResultActions prepareRequest(Long holdId) throws Exception {
        return mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", memberId)
                .contentType("application/json").content("{\"holdId\":%d}".formatted(holdId)));
    }
    private PaymentPrepareResponse prepare(Long holdId, int expected) throws Exception {
        return json.readValue(prepareRequest(holdId).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString(), PaymentPrepareResponse.class);
    }
    private ResultActions confirm(PaymentPrepareResponse prepared) throws Exception {
        return mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id", memberId)
                .contentType("application/json").content(json.writeValueAsString(
                        new PaymentConfirmRequest(prepared.orderId(), "consumer-key", prepared.amount()))));
    }
    private ResultActions preview(Long holdId) throws Exception {
        return mvc.perform(get("/api/user/reservations/{id}/refund-preview", holdId).header("X-Member-Id", memberId))
                .andExpect(status().isOk());
    }
    private Map<String, Object> row(Long holdId) { return jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId); }
    private int reservationCount() { return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE store_id = ?", Integer.class, storeId); }
    private int historyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_history h JOIN payment p ON p.id = h.payment_id JOIN reservation r ON r.id = p.reservation_id WHERE r.store_id = ?", Integer.class, storeId);
    }
    private void assertOriginal(Long holdId) {
        assertThat(row(holdId)).containsEntry("deposit_amount_snapshot", AMOUNT).containsEntry("refund_policy_snapshot", "STANDARD");
    }
    private void assertNoPaymentOrNotification() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment p JOIN reservation r ON r.id = p.reservation_id WHERE r.store_id = ?", Integer.class, storeId)).isZero();
        assertThat(historyCount()).isZero();
        verifyNoInteractions(toss, emitter, directEmitter);
    }
    private void awaitStoreWaiter() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                AND wait_event_type = 'Lock' AND query ILIKE '%store%'
                """, Integer.class)).isPositive());
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("test synchronization timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
    private static ReservationDepositTermsReader.Terms paid(long amount, RefundPolicy policy) {
        return new ReservationDepositTermsReader.Terms(true, amount, policy);
    }

    @TestConfiguration
    static class SourceFixture {
        @Bean TestTermsReader testTermsReader() { return new TestTermsReader(); }
    }
    static class TestTermsReader implements ReservationDepositTermsReader {
        final Map<Long, Supplier<Terms>> terms = new ConcurrentHashMap<>();
        volatile Consumer<Store> onRead = store -> {};
        @Override public Terms read(Store lockedStore) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            onRead.accept(lockedStore);
            Supplier<Terms> saved = terms.get(lockedStore.getId());
            return saved == null ? null : saved.get();
        }
    }
    @TestConfiguration
    static class ClockFixture {
        @Bean @Primary MutableClock depositClock() { return new MutableClock(new AtomicReference<>(NOW), ZoneId.of("Asia/Seoul")); }
    }
    static final class MutableClock extends Clock {
        private final AtomicReference<OffsetDateTime> current;
        private final ZoneId zone;
        MutableClock(AtomicReference<OffsetDateTime> current, ZoneId zone) { this.current = current; this.zone = zone; }
        void set(OffsetDateTime now) { current.set(now); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(current, zone); }
        @Override public Instant instant() { return current.get().toInstant(); }
    }
}
