package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.dto.PaymentPrepareRequest;
import com.catchtable.payment.dto.PaymentPrepareResponse;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.entity.RefundPolicy;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.reservation.service.ReservationPaymentService;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.store.entity.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PaymentPrepareServiceTest.ClockFixture.class, FakeTossPaymentsClient.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentPrepareServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    private static final OffsetDateTime EXPIRY = NOW.plusMinutes(4);
    private static final OffsetDateTime START = NOW.plusDays(2);
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentPrepareService service;
    @Autowired ReservationPaymentService approvals;
    @Autowired ReservationService reservations;
    @Autowired ReservationRepository repository;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired SseEmitterRepository emitters;
    @SpyBean FakeTossPaymentsClient toss;
    PaymentTestData data;
    Long memberId, ownerId, foreignId, storeId, holdId;
    SseEmitter emitter;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        toss.reset();
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        ownerId = data.member("OWNER");
        foreignId = data.member("CUSTOMER");
        storeId = data.store(ownerId);
        // 신뢰할 수 있는 서버 생성 경로의 저장 결과를 대체한다. 공개 유료 생성 E2E가 아니다.
        holdId = transactions.execute(tx -> {
            Long tableId = jdbc.queryForObject("SELECT id FROM store_table WHERE store_id = ?", Long.class, storeId);
            return repository.saveAndFlush(Reservation.held(entityManager.getReference(Member.class, memberId),
                    entityManager.getReference(Store.class, storeId), tableId, START, START.plusHours(1),
                    2, NOW.minusMinutes(1), 10_000, RefundPolicy.FLEXIBLE)).getId();
        });
        emitter = mock(SseEmitter.class);
        emitters.save(memberId, emitter);
    }

    @AfterEach
    void cleanUp() {
        emitters.delete(memberId, emitter);
        jdbc.update("DELETE FROM reservation_refund_request WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        data.deleteStore(storeId, List.of(memberId, ownerId, foreignId));
        clock.set(NOW);
    }

    @Test
    void httpPrepareUsesOnlyTheSavedAmountAndCreatesOneInitialHistory() throws Exception {
        mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", memberId)
                        .contentType("application/json")
                        .content("""
                                {"holdId":%d,"amount":1,"refundPolicy":"STRICT","orderId":"client-order",
                                 "expiresAt":"2099-01-01T00:00:00+09:00","memberId":%d}
                                """.formatted(holdId, foreignId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.reservationId").value(holdId))
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.orderId").value(startsWith("ct-")))
                .andExpect(jsonPath("$.orderName").value("예약금"))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRY.toInstant().toString()))
                .andExpect(jsonPath("$.serverTime").value(NOW.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                .andExpect(jsonPath("$.remainingSeconds").value(240));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT h.previous_status, h.status, h.source, h.detail FROM payment_status_history h
                JOIN payment p ON p.id = h.payment_id WHERE p.reservation_id = ?
                """, holdId)).containsEntry("previous_status", "READY").containsEntry("status", "READY")
                .containsEntry("source", "USER").containsEntry("detail", "결제 준비");
        assertThat(jdbc.queryForObject("SELECT length(order_id) FROM payment WHERE reservation_id = ?",
                Integer.class, holdId)).isBetween(1, 64);
        assertThat(jdbc.queryForObject("SELECT refund_policy_snapshot FROM reservation WHERE id = ?",
                String.class, holdId)).isEqualTo("FLEXIBLE");
        assertThat(state()).isEqualTo("PENDING_PAYMENT");
        assertNoPgOrNotification();
    }

    @Test
    void repeatedHttpPrepareReusesTheOriginalReadyOrderWithoutTouchingAnyRow() throws Exception {
        PaymentPrepareResponse first = httpPrepared(201);
        var originalPayment = jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", first.paymentId());
        var originalHold = jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId);
        var originalHistory = jdbc.queryForList("SELECT * FROM payment_status_history WHERE payment_id = ?",
                first.paymentId());
        clock.set(NOW.plusMinutes(2));
        PaymentPrepareResponse second = httpPrepared(200);
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.orderName()).isEqualTo(first.orderName());
        assertThat(second.amount()).isEqualTo(first.amount());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(second.serverTime()).isEqualTo(NOW.plusMinutes(2));
        assertThat(second.remainingSeconds()).isEqualTo(120);
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", first.paymentId())).isEqualTo(originalPayment);
        assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId)).isEqualTo(originalHold);
        assertThat(jdbc.queryForList("SELECT * FROM payment_status_history WHERE payment_id = ?", first.paymentId()))
                .isEqualTo(originalHistory);
        assertNoPgOrNotification();
    }

    @Test
    @Timeout(20)
    void concurrentPrepareCreatesExactlyOneOrderAndHistory() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.prepare(memberId, request());
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<PaymentPrepareService.Prepared>();
            for (var call : calls) results.add(call.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(PaymentPrepareService.Prepared::created).count()).isEqualTo(1);
            assertThat(results.stream().map(p -> p.response().paymentId()).distinct()).hasSize(1);
            assertThat(results.stream().map(p -> p.response().orderId()).distinct()).hasSize(1);
            assertThat(results.stream().map(p -> p.response().expiresAt().toInstant()).distinct())
                    .containsExactly(EXPIRY.toInstant());
        }
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
        assertNoPgOrNotification();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"holdId\":null}", "{\"holdId\":0}", "{\"holdId\":-1}",
            "{\"reservationId\":1}", "{\"holdId\":\"bad\"}", "null"})
    void invalidRequestsDoNotCreatePayment(String body) throws Exception {
        mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", memberId)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertEmpty();
    }

    @Test
    void identityRoleAndOwnershipFollowTheCurrentConventions() throws Exception {
        mvc.perform(post("/api/user/payments/prepare").contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", "bad")
                        .contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
        httpPrepare(ownerId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
        httpPrepare(Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        httpPrepare(foreignId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", memberId)
                        .contentType("application/json").content("{\"holdId\":9223372036854775807}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        assertEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "0"})
    void missingOrFreeSnapshotIsNeverFilledFromCurrentSettings(String amount) throws Exception {
        jdbc.update("UPDATE reservation SET deposit_amount_snapshot = " + amount
                + ", refund_policy_snapshot = NULL WHERE id = ?", holdId);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT refund_policy_snapshot FROM reservation WHERE id = ?", String.class,
                holdId)).isNull();
        assertEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit_amount_snapshot = -1", "refund_policy_snapshot = NULL",
            "refund_policy_snapshot = 'UNKNOWN'", "deposit_amount_snapshot = 0"})
    void databaseStillRejectsMalformedSnapshotPairs(String assignment) {
        assertThatThrownBy(() -> jdbc.update("UPDATE reservation SET " + assignment + " WHERE id = ?", holdId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "CANCELLED", "EXPIRED", "COMPLETED", "NO_SHOW"})
    void terminalOrConfirmedReservationsCannotBecomeNewOrders(String state) throws Exception {
        jdbc.update("UPDATE reservation SET status = ?, confirmed_at = CASE WHEN ? IN ('CONFIRMED','COMPLETED','NO_SHOW') THEN ? ELSE NULL END WHERE id = ?",
                state, state, NOW, holdId);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS"));
        assertThat(state()).isEqualTo(state);
        assertEmpty();
    }

    @Test
    void enteredHoldCannotPrepare() throws Exception {
        jdbc.update("UPDATE reservation SET checked_in_at = ?, checked_in_by_member_id = ? WHERE id = ?",
                NOW, ownerId, holdId);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS"));
        assertEmpty();
    }

    @Test
    void alteredExpiryIsInvalidOriginalDataAndIsNotExtendedAgain() {
        jdbc.update("UPDATE reservation SET hold_expires_at = ? WHERE id = ?", EXPIRY.plusMinutes(1), holdId);
        assertCode(() -> service.prepare(memberId, request()), ErrorCode.PAYMENT_MISMATCH);
        assertEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void exactExpiryAndAfterwardsCannotCreateAnOrder(long microsAfter) throws Exception {
        clock.set(EXPIRY.plusNanos(microsAfter * 1000));
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS"));
        assertEmpty();
    }

    @Test
    void oneMicrosecondBeforeExpiryCanPrepareButReadyDoesNotProtectOrExtendTheHold() throws Exception {
        clock.set(EXPIRY.minusNanos(1000));
        var prepared = httpPrepared(201);
        assertThat(prepared.remainingSeconds()).isZero();
        assertThat(prepared.expiresAt()).isEqualTo(EXPIRY);
        clock.set(EXPIRY);
        httpPrepare(memberId).andExpect(status().isConflict());
        assertCode(() -> approvals.confirm(memberId, new PaymentConfirmRequest(prepared.orderId(), "too-late", 10_000)),
                ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(reservations.expireHolds()).isEqualTo(1);
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
        assertNoPgOrNotification();
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,0", "DONE,0", "PARTIAL_REFUNDED,1000", "REFUNDED,10000"})
    void unknownAndCompletedPaymentsDirectTheCustomerToExistingStatus(String paymentStatus, long refunded)
            throws Exception {
        data.payment(memberId, holdId, paymentStatus, refunded);
        assertStatusQueryConflict();
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isZero();
        assertNoPgOrNotification();
    }

    @Test
    void quarantinedReservationStaysQuarantinedEvenWithoutAPayment() throws Exception {
        jdbc.update("UPDATE reservation SET approval_quarantined = TRUE WHERE id = ?", holdId);
        var original = jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId);
        assertStatusQueryConflict();
        assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId)).isEqualTo(original);
        assertEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELED"})
    void verifiedFailureDoesNotInventANewPaymentRetryPolicy(String paymentStatus) throws Exception {
        data.payment(memberId, holdId, paymentStatus, 0);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isZero();
        assertNoPgOrNotification();
    }

    @Test
    void multipleRowsAreNotRepairedOrTreatedAsAReadyReuse() throws Exception {
        data.payment(memberId, holdId, "FAILED", 0);
        data.payment(memberId, holdId, "READY", 0);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(paymentCount()).isEqualTo(2);
        assertThat(historyCount()).isZero();
        assertNoPgOrNotification();
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount = 9999", "order_id = ' '", "order_name = ' '", "payment_key = 'unbound-key'",
            "approved_at = now()", "method = '카드'", "receipt_url = 'https://receipt'",
            "failure_code = 'FAILED'", "failure_message = 'failed'"})
    void inconsistentReadyPaymentCannotBeReused(String assignment) throws Exception {
        data.payment(memberId, holdId, "READY", 0);
        jdbc.update("UPDATE payment SET " + assignment + " WHERE reservation_id = ?", holdId);
        var original = jdbc.queryForList("SELECT * FROM payment WHERE reservation_id = ?", holdId);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(jdbc.queryForList("SELECT * FROM payment WHERE reservation_id = ?", holdId)).isEqualTo(original);
        assertThat(historyCount()).isZero();
        assertNoPgOrNotification();
    }

    @Test
    void readyPaymentMustBelongToTheSameMemberAsTheHold() throws Exception {
        data.payment(foreignId, holdId, "READY", 0);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isZero();
        assertNoPgOrNotification();
    }

    @Test
    void refundFenceIsPreservedWithoutCreatingAPayment() throws Exception {
        jdbc.update("UPDATE reservation SET refund_in_progress = TRUE WHERE id = ?", holdId);
        httpPrepare(memberId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_DATA_NOT_READY"));
        assertThat(jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id = ?", Boolean.class, holdId))
                .isTrue();
        assertEmpty();
    }

    @Test
    void orderAndInitialHistoryRollBackTogether() {
        transactions.executeWithoutResult(tx -> {
            assertThat(service.prepare(memberId, request()).created()).isTrue();
            tx.setRollbackOnly();
        });
        assertEmpty();
        assertThat(service.prepare(memberId, request()).created()).isTrue();
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void prepareWaitsForReservationBeforeLoadingTheChangedPayment() throws Exception {
        var prepared = service.prepare(memberId, request()).response();
        try (var executor = Executors.newSingleThreadExecutor()) {
            AtomicReference<java.util.concurrent.Future<?>> call = new AtomicReference<>();
            transactions.executeWithoutResult(tx -> {
                repository.findByIdForUpdate(holdId).orElseThrow();
                call.set(executor.submit(() -> assertCode(() -> service.prepare(memberId, request()),
                        ErrorCode.INVALID_PAYMENT_STATUS)));
                awaitReservationWaiter();
                jdbc.update("UPDATE payment SET status = 'CANCELED' WHERE id = ?", prepared.paymentId());
            });
            call.get().get(10, TimeUnit.SECONDS);
        }
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
        assertNoPgOrNotification();
    }

    @Test
    @Timeout(20)
    void waitingPrepareUsesTheClockAfterAcquiringTheLockAndLosesToExpiry() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            AtomicReference<java.util.concurrent.Future<?>> call = new AtomicReference<>();
            transactions.executeWithoutResult(tx -> {
                repository.findByIdForUpdate(holdId).orElseThrow();
                call.set(executor.submit(() -> assertCode(() -> service.prepare(memberId, request()),
                        ErrorCode.INVALID_PAYMENT_STATUS)));
                awaitReservationWaiter();
                clock.set(EXPIRY);
                repository.expireHolds(storeId, EXPIRY);
            });
            call.get().get(10, TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("EXPIRED");
        assertEmpty();
    }

    @Test
    @Timeout(20)
    void inProgressApprovalBlocksPrepareAndLateDoneConfirmsTheSameSlotOnce() throws Exception {
        var prepared = service.prepare(memberId, request()).response();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            transactions.executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM reservation WHERE id = ? FOR UPDATE NOWAIT", Long.class, holdId);
                jdbc.queryForObject("SELECT id FROM payment WHERE id = ? FOR UPDATE NOWAIT", Long.class, prepared.paymentId());
            });
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(toss).confirm(anyString(), anyString(), anyLong(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var confirmation = executor.submit(() -> approvals.confirm(memberId,
                    new PaymentConfirmRequest(prepared.orderId(), "original-key", prepared.amount())));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                var correlation = jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId);
                clock.set(EXPIRY.plusSeconds(1));
                assertStatusQueryConflict();
                assertThat(reservations.expireHolds()).isZero();
                assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId)).isEqualTo(correlation);
                assertThat(paymentCount()).isEqualTo(1);
                assertThat(historyCount()).isEqualTo(1);
            } finally {
                finish.countDown();
            }
            assertThat(confirmation.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.DONE);
        }
        assertThat(approvals.confirm(memberId, new PaymentConfirmRequest(prepared.orderId(), "original-key", 10_000))
                .status()).isEqualTo(PaymentStatus.DONE);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT reservation_start_at FROM reservation WHERE id = ?",
                OffsetDateTime.class, holdId)).isAtSameInstantAs(START);
        assertThat(historyCount()).isEqualTo(2);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        assertStatusQueryConflict();
    }

    @Test
    void unknownApprovalKeepsOriginalCorrelationAndCannotPrepareAnotherOrder() throws Exception {
        var prepared = service.prepare(memberId, request()).response();
        toss.failNext(FakeTossPaymentsClient.timeout());
        assertCode(() -> approvals.confirm(memberId,
                new PaymentConfirmRequest(prepared.orderId(), "unknown-key", 10_000)), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        var original = jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId);
        var originalPayment = jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", prepared.paymentId());
        clock.set(EXPIRY);
        assertStatusQueryConflict();
        assertThat(reservations.expireHolds()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", holdId)).isEqualTo(original);
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", prepared.paymentId())).isEqualTo(originalPayment);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(2);
        verifyNoInteractions(emitter);
    }

    @Test
    void trustedSavedSnapshotCanPrepareConfirmRefundAndThenCancelThroughExistingApis() throws Exception {
        var prepared = httpPrepared(201);
        doAnswer(call -> {
            // 커밋 후 별도 연결에서도 CONFIRMED가 보이는지 확인한다.
            try (var connection = jdbc.getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT confirmed_at IS NOT NULL FROM reservation WHERE id = ?")) {
                statement.setLong(1, holdId);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id", memberId)
                        .contentType("application/json").content(json.writeValueAsString(
                                new PaymentConfirmRequest(prepared.orderId(), "flow-key", prepared.amount()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(post("/api/user/payments/{id}/cancel", prepared.paymentId())
                        .header("X-Member-Id", memberId).header("Idempotency-Key", "flow-refund")
                        .contentType("application/json").content("{\"cancelReason\":\"고객 요청\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(10000))
                .andExpect(jsonPath("$.totalRefundedAmount").value(10000));
        assertThat(state()).isEqualTo("CONFIRMED");
        mvc.perform(post("/api/user/reservations/{id}/cancel", holdId).header("X-Member-Id", memberId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertStatusQueryConflict();
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(3);
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void trustedSavedSnapshotCanUseTheExistingRefundAndCancelIdempotencyContract() throws Exception {
        var prepared = httpPrepared(201);
        approvals.confirm(memberId, new PaymentConfirmRequest(prepared.orderId(), "cancel-flow-key", prepared.amount()));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/user/reservations/{id}/cancel", holdId).header("X-Member-Id", memberId)
                            .header("Idempotency-Key", "prepare-cancel"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(3);
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void publicFreeHoldStillHasNoPaidSnapshotAndCannotBecomeAPayment() throws Exception {
        jdbc.update("INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time) VALUES (?, 7, '09:00', '23:00')", storeId);
        var free = reservations.createHold(memberId, new CreateReservationRequest(storeId, NOW.plusHours(6), 2));
        mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", memberId)
                        .contentType("application/json").content("{\"holdId\":%d}".formatted(free.holdId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT deposit_amount_snapshot FROM reservation WHERE id = ?", Long.class,
                free.holdId())).isNull();
        reservations.releaseHold(memberId, free.holdId());
        assertThat(reservations.getHold(memberId, free.holdId()).status()).isEqualTo(ReservationStatus.CANCELLED);
        assertEmpty();
    }

    private PaymentPrepareRequest request() { return new PaymentPrepareRequest(holdId); }
    private String body() { return "{\"holdId\":%d}".formatted(holdId); }
    private ResultActions httpPrepare(Long id) throws Exception {
        return mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", id)
                .contentType("application/json").content(body()));
    }
    private PaymentPrepareResponse httpPrepared(int status) throws Exception {
        return json.readValue(httpPrepare(memberId).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(), PaymentPrepareResponse.class);
    }
    private void assertStatusQueryConflict() throws Exception {
        httpPrepare(memberId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_STATUS_CHECK_REQUIRED"))
                .andExpect(jsonPath("$.message").value(containsString("기존 결제 상태를 조회")));
    }
    private void assertEmpty() {
        assertThat(paymentCount()).isZero();
        assertThat(historyCount()).isZero();
        assertNoPgOrNotification();
    }
    private int paymentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class, holdId);
    }
    private int historyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id IN (SELECT id FROM payment WHERE reservation_id = ?)", Integer.class, holdId);
    }
    private String state() {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, holdId);
    }
    private void assertNoPgOrNotification() {
        verify(toss, never()).confirm(anyString(), anyString(), anyLong(), anyString());
        verify(toss, never()).cancel(anyString(), any(), anyString(), anyString());
        verify(toss, never()).getByOrderId(anyString());
        verify(toss, never()).getByPaymentKey(anyString());
        verifyNoInteractions(emitter);
    }
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
    private void awaitReservationWaiter() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE '%reservation%'
                """, Integer.class)).isPositive());
    }

    @TestConfiguration
    static class ClockFixture {
        @Bean @Primary MutableClock prepareClock() { return new MutableClock(new AtomicReference<>(NOW), ZoneId.of("Asia/Seoul")); }
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
