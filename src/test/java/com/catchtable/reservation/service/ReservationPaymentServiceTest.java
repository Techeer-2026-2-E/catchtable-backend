package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.OwnerReservationUpdateRequest;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.repository.StoreTableRepository;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.StoreTableUpdateRequest;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.store.service.BusinessHourService;
import com.catchtable.store.service.StoreTableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReservationPaymentServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationPaymentService service;
    @Autowired private ReservationService reservations;
    @Autowired private ReservationRepository repository;
    @Autowired private StoreTableRepository tables;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MockMvc mvc;
    @Autowired private OwnerReservationService owners;
    @Autowired private StoreTableService tableService;
    @Autowired private BusinessHourService hours;
    @Autowired private SseEmitterRepository emitters;
    @MockBean private TossPaymentsClient toss;
    @MockBean private Clock clock;

    private final AtomicReference<OffsetDateTime> now = new AtomicReference<>();
    private PaymentTestData data;
    private Long memberId;
    private Long ownerId;
    private Long storeId;
    private Long reservationId;
    private Long paymentId;
    private String orderId;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        now.set(NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenAnswer(call -> now.get().toInstant());
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        ownerId = data.member("OWNER");
        storeId = data.store(ownerId);
        reservationId = data.hold(memberId, storeId, 0);
        jdbc.update("""
                UPDATE reservation SET requested_at = ?, hold_expires_at = ?,
                    deposit_amount_snapshot = 10000, refund_policy_snapshot = 'FLEXIBLE' WHERE id = ?
                """, NOW.minusMinutes(1), NOW.plusMinutes(4), reservationId);
        paymentId = data.payment(memberId, reservationId, "READY", 0);
        orderId = data.orderId(paymentId);
        emitter = mock(SseEmitter.class);
        emitters.save(memberId, emitter);
        jdbc.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                VALUES (?, 7, '09:00', '23:00')
                """, storeId);
    }

    @AfterEach
    void cleanUp() {
        emitters.delete(memberId, emitter);
        jdbc.update("UPDATE reservation SET approval_started_at = NULL, approval_payment_id = NULL, approval_order_id = NULL, approval_payment_key = NULL WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        data.deleteStore(storeId, List.of(memberId, ownerId));
    }

    @Test
    void httpConfirmationAndReplayConfirmExactlyOnce() throws Exception {
        doAnswer(call -> {
            // 다른 연결의 READ COMMITTED 조회로 실제 커밋 이후임을 확인한다.
            try (var connection = jdbc.getDataSource().getConnection();
                 var sql = connection.prepareStatement("SELECT status FROM reservation WHERE id = ?")) {
                sql.setLong(1, reservationId);
                try (var result = sql.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("CONFIRMED");
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(response("DONE"));
        mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id", memberId)
                        .contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.reservationId").value(reservationId));
        OffsetDateTime confirmedAt = confirmedAt();
        now.set(NOW.plusMinutes(2));
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(confirmedAt()).isEqualTo(confirmedAt);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(historyCount()).isEqualTo(1);
        assertThat(fenced()).isTrue();
        verify(toss, times(1)).confirm("pk-test", orderId, 10000, "confirm-" + orderId);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertFailure(() -> service.confirm(memberId, new PaymentConfirmRequest(orderId, "pk-other", 10000)),
                ErrorCode.PAYMENT_MISMATCH);
    }

    @Test
    void aVerifiedFailureIs200FailedAndNeverConfirmsTheReservation() throws Exception {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(FakeTossPaymentsClient.rejected());
        mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id", memberId)
                        .contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(confirmedAt()).isNull();
        assertThat(fenced()).isFalse();
        verifyNoInteractions(emitter);
        var hold = reservations.getHold(memberId, reservationId);
        assertThat(tables.countAvailable(storeId, 2, hold.reservationStartAt(), hold.reservationEndAt(), NOW)).isEqualTo(1);
        now.set(NOW.plusMinutes(4));
        assertThat(reservations.getHold(memberId, reservationId).status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void aReadyResponseRetainsTheFenceAndDoesNotConfirm() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(response("IN_PROGRESS"));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        assertThat(confirmedAt()).isNull();
        assertThat(fenced()).isTrue();
        now.set(NOW.plusMinutes(5));
        assertProtected();
        verifyNoInteractions(emitter);
    }

    @Test
    void unknownResultIsPersistedBefore409AndSameKeyCanResume() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(FakeTossPaymentsClient.timeout()).thenReturn(response("DONE"));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
                .isEqualTo("TIMEOUT");
        assertThat(fenced()).isTrue();
        assertFailure(() -> service.confirm(memberId, new PaymentConfirmRequest(orderId, "different-key", 10000)),
                ErrorCode.PAYMENT_MISMATCH);
        now.set(NOW.plusMinutes(5));
        assertProtected();
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(historyCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5})
    void expiredReadyBeforeApprovalCannotCallTossOrExtendItsDeadline(int minutes) {
        now.set(NOW.plusMinutes(minutes));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(fenced()).isFalse();
        assertThat(reservations.getHold(memberId, reservationId).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.getHold(memberId, reservationId).expiresAt().toInstant())
                .isEqualTo(NOW.plusMinutes(4).toInstant());
        verifyNoInteractions(toss);
    }

    @Test
    void releasedHoldWinsBeforeTheApprovalFenceAndNeverCallsToss() {
        reservations.releaseHold(memberId, reservationId);
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(state()).isEqualTo("CANCELLED");
        verifyNoInteractions(toss);
    }

    @Test
    void ownershipMoneyAndMissingOriginalTermsAreCheckedBeforeAnyFenceOrExternalCall() throws Exception {
        mvc.perform(post("/api/user/payments/confirm").contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
        assertFailure(() -> service.confirm(ownerId, request()), ErrorCode.PAYMENT_NOT_FOUND);
        assertFailure(() -> service.confirm(memberId, new PaymentConfirmRequest(orderId, "pk-test", 9999)),
                ErrorCode.PAYMENT_MISMATCH);
        jdbc.update("UPDATE reservation SET deposit_amount_snapshot = NULL, refund_policy_snapshot = NULL WHERE id = ?", reservationId);
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_MISMATCH);
        assertThat(fenced()).isFalse();
        verifyNoInteractions(toss);
    }

    @Test
    void anOuterTransactionIsRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> transactions.execute(status -> service.confirm(memberId, request())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fenced()).isFalse();
        verifyNoInteractions(toss);
    }

    @Test
    @Timeout(20)
    void externalCallHoldsNoLocksAndEveryExpiryAndCapacityPathUsesThePersistedFence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM reservation WHERE id = ? FOR UPDATE NOWAIT", Long.class, reservationId);
                jdbc.queryForObject("SELECT id FROM payment WHERE id = ? FOR UPDATE NOWAIT", Long.class, paymentId);
            });
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return response("DONE");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.confirm(memberId, request()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                now.set(NOW.plusMinutes(5));
                assertProtected();
            } finally {
                finish.countDown();
            }
            // 유효한 선점에서 시작한 승인이라 최초 기한 뒤에도 같은 자리를 확정한다.
            assertThat(result.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.DONE);
            assertThat(state()).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
                    .isEqualTo("DONE");
        }
    }

    @Test
    @Timeout(20)
    void concurrentSameKeyApprovalsConfirmTheReservationAndLedgerOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            return response("DONE");
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.confirm(memberId, request()));
            var second = executor.submit(() -> service.confirm(memberId, request()));
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.DONE);
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.DONE);
        }
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(historyCount()).isEqualTo(1);
        assertThat(fenced()).isTrue();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void startsOneMicrosecondBeforeExpiryAndConfirmsTheSameSlotAfterExpiry() {
        now.set(NOW.plusMinutes(4).minusNanos(1000));
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            now.set(NOW.plusMinutes(10));
            return response("DONE");
        });
        Long tableId = reservations.getHold(memberId, reservationId).tableId();
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(reservations.getReservation(memberId, reservationId).tableId()).isEqualTo(tableId);
        assertThat(confirmedAt()).isAtSameInstantAs(NOW.plusMinutes(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "DONE", "PARTIAL_REFUNDED", "REFUNDED"})
    void legacyQuarantineProtectsAllSlotPathsAndNeverInventsAnApproval(String paymentStatus) {
        if (paymentStatus.equals("TIMEOUT")) {
            jdbc.update("UPDATE payment SET status = 'TIMEOUT' WHERE id = ?", paymentId);
        } else {
            long refund = paymentStatus.equals("PARTIAL_REFUNDED") ? 1000 : paymentStatus.equals("REFUNDED") ? 10000 : 0;
            jdbc.update("UPDATE payment SET status = ?, payment_key = 'legacy-key', approved_at = ?, refunded_amount = ? WHERE id = ?",
                    paymentStatus, NOW, refund, paymentId);
        }
        jdbc.update("UPDATE reservation SET approval_quarantined = TRUE WHERE id = ?", reservationId);
        now.set(NOW.plusMinutes(5));
        assertProtected();
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        assertThat(jdbc.queryForMap("SELECT approval_started_at, approval_payment_id, approval_order_id, approval_payment_key FROM reservation WHERE id = ?", reservationId).values())
                .containsOnlyNulls();
        verifyNoInteractions(toss, emitter);
    }

    @Test
    void failureAfterExpiryUnfencesAndExpiresWithoutConfirmingOrRefunding() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            now.set(NOW.plusMinutes(5));
            throw FakeTossPaymentsClient.rejected();
        });
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(fenced()).isFalse();
        assertThat(confirmedAt()).isNull();
        verifyNoInteractions(emitter);
    }

    @Test
    void definiteFailureReleasesImmediatelyAndExplicitReleaseReplayIsUnchanged() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(FakeTossPaymentsClient.rejected());
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.FAILED);
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.INVALID_PAYMENT_STATUS);
        reservations.releaseHold(memberId, reservationId);
        assertThat(state()).isEqualTo("CANCELLED");
        verifyNoInteractions(emitter);
    }

    @Test
    void unexpectedNetworkErrorKeepsTheBoundAttemptAndDoesNotMaskTheOriginalException() {
        var networkError = new IllegalStateException("fake transport failure");
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(networkError);
        assertThatThrownBy(() -> service.confirm(memberId, request())).isSameAs(networkError);
        now.set(NOW.plusMinutes(5));
        assertProtected();
        assertThat(paymentState()).isEqualTo("READY");
        verifyNoInteractions(emitter);
    }

    @Test
    void providerKeyMismatchPreservesTheCommittedPaymentAndNeverConfirms() {
        TossPaymentResponse good = response("DONE");
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(new TossPaymentResponse(
                "unexpected-key", orderId, good.orderName(), "DONE", good.method(), 10000, 10000,
                NOW, good.receipt(), null));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_MISMATCH);
        assertThat(paymentState()).isEqualTo("DONE");
        assertThat(data.paymentKey(paymentId)).isEqualTo("unexpected-key");
        assertThat(confirmedAt()).isNull();
        now.set(NOW.plusMinutes(5));
        assertProtected();
        verifyNoInteractions(emitter);
    }

    @Test
    void changingTheOrderDuringTheNetworkCallCannotHijackTheBoundAttempt() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            jdbc.update("UPDATE payment SET order_id = ? WHERE id = ?", "changed-" + orderId, paymentId);
            return response("DONE");
        });
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_MISMATCH);
        assertFailure(() -> service.confirm(memberId, new PaymentConfirmRequest("changed-" + orderId, "pk-test", 10000)),
                ErrorCode.PAYMENT_MISMATCH);
        now.set(NOW.plusMinutes(5));
        assertProtected();
        verifyNoInteractions(emitter);
    }

    @Test
    @Timeout(20)
    void aCompetingKeyIsRejectedWhileTheOriginalApprovalRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return response("DONE");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.confirm(memberId, request()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertFailure(() -> service.confirm(memberId, new PaymentConfirmRequest(orderId, "pk-competitor", 10000)),
                        ErrorCode.PAYMENT_MISMATCH);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.DONE);
        }
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void expiredApprovalBlocksOwnerChangesIntoTheHeldSlotAndRollsBack() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(response("IN_PROGRESS"));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        var hold = reservations.getHold(memberId, reservationId);
        Long otherReservation = jdbc.queryForObject("""
                INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at,
                    reservation_end_at, party_size, status, requested_at, confirmed_at, deposit_amount_snapshot)
                VALUES (?, ?, ?, ?, ?, 2, 'CONFIRMED', ?, ?, 0) RETURNING id
                """, Long.class, memberId, storeId, hold.tableId(), hold.reservationEndAt(),
                hold.reservationEndAt().plusHours(1), NOW, NOW);
        now.set(NOW.plusMinutes(5));
        assertThatThrownBy(() -> owners.updateReservation(ownerId, otherReservation,
                new OwnerReservationUpdateRequest(hold.reservationStartAt(), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        assertThat(reservations.getReservation(memberId, otherReservation).reservationStartAt())
                .isAtSameInstantAs(hold.reservationEndAt());
        assertThat(state()).isEqualTo("PENDING_PAYMENT");
        verifyNoInteractions(emitter);
    }

    @Test
    void aConfirmationRollbackKeepsTheCommittedApprovalAndSendsNoNotification() throws Exception {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(response("DONE"));
        String trigger = "reject_confirmation_" + reservationId;
        jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id = " + reservationId + " AND NEW.status = 'CONFIRMED' THEN "
                + "RAISE EXCEPTION 'test confirmation rollback'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON reservation FOR EACH ROW EXECUTE FUNCTION "
                + trigger + "()");
        try {
            assertThatThrownBy(() -> service.confirm(memberId, request())).isInstanceOf(RuntimeException.class);
            assertThat(paymentState()).isEqualTo("DONE");
            assertThat(state()).isEqualTo("PENDING_PAYMENT");
            assertThat(confirmedAt()).isNull();
            assertThat(fenced()).isTrue();
            verifyNoInteractions(emitter);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON reservation");
            jdbc.execute("DROP FUNCTION " + trigger + "()");
        }
        now.set(NOW.plusMinutes(5));
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.DONE);
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void unknownRetryAtOrAfterTheFifteenDayIdempotencyLimitNeverCallsTossAgain(int extraSeconds) {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(FakeTossPaymentsClient.timeout());
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        now.set(NOW.plusDays(15).plusSeconds(extraSeconds));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        assertThat(paymentState()).isEqualTo("TIMEOUT");
        assertThat(state()).isEqualTo("PENDING_PAYMENT");
        assertThat(fenced()).isTrue();
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(emitter);
    }

    @Test
    void verifiedDoneAfterFifteenDaysCanFinishWithoutAnotherExternalApproval() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(FakeTossPaymentsClient.timeout());
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        jdbc.update("UPDATE payment SET status = 'DONE', payment_key = 'pk-test', approved_at = ? WHERE id = ?", NOW, paymentId);
        now.set(NOW.plusDays(16));
        assertThat(service.confirm(memberId, request()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(state()).isEqualTo("CONFIRMED");
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void aKeyBoundToAnotherPendingPaymentCannotBeUsedForANewOrder() {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(response("IN_PROGRESS"));
        assertFailure(() -> service.confirm(memberId, request()), ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        Long otherHold = data.hold(memberId, storeId, 1);
        jdbc.update("""
                UPDATE reservation SET requested_at = ?, hold_expires_at = ?,
                    deposit_amount_snapshot = 10000, refund_policy_snapshot = 'FLEXIBLE' WHERE id = ?
                """, NOW.minusMinutes(1), NOW.plusMinutes(4), otherHold);
        Long otherPayment = data.payment(memberId, otherHold, "READY", 0);
        assertFailure(() -> service.confirm(memberId, new PaymentConfirmRequest(data.orderId(otherPayment), "pk-test", 10000)),
                ErrorCode.PAYMENT_KEY_ALREADY_USED);
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(emitter);
    }

    @ParameterizedTest
    @CsvSource({"' ',pk,10000", "order,' ',10000", "order,pk,0", "order,pk,-1"})
    void invalidHttpInputsDoNotWriteProgressOrCallToss(String order, String key, long amount) throws Exception {
        mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id", memberId)
                        .contentType("application/json").content("{\"orderId\":\"" + order + "\",\"paymentKey\":\"" + key + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest());
        assertThat(fenced()).isFalse();
        verifyNoInteractions(toss, emitter);
    }

    private void assertProtected() {
        assertThat(reservations.expireHolds()).isZero();
        var hold = reservations.getHold(memberId, reservationId);
        assertThat(hold.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(hold.remainingSeconds()).isZero();
        assertThat(hold.expiresAt().toInstant()).isEqualTo(NOW.plusMinutes(4).toInstant());
        assertThat(reservations.getReservation(memberId, reservationId).status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertFailure(() -> reservations.releaseHold(memberId, reservationId), ErrorCode.RESERVATION_NOT_CANCELLABLE);
        assertThat(repository.findActiveForStore(storeId, now.get())).hasSize(1);
        assertThat(repository.existsConflictingTableReservation(hold.tableId(), now.get(), true, 1, 4)).isTrue();
        assertThat(tables.countAvailable(storeId, 2, hold.reservationStartAt(), hold.reservationEndAt(), now.get())).isZero();
        boolean allocationBlocked = transactions.execute(status -> tables.findAvailableForUpdate(storeId, 2,
                hold.reservationStartAt(), hold.reservationEndAt(), null).isEmpty());
        assertThat(allocationBlocked).isTrue();
        assertThatThrownBy(() -> reservations.createHold(memberId,
                new CreateReservationRequest(storeId, hold.reservationStartAt(), 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        assertFailure(() -> tableService.updateTable(storeId, hold.tableId(),
                new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE)), ErrorCode.TABLE_HAS_RESERVATIONS);
        assertFailure(() -> hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of())),
                ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);
        assertThat(state()).isEqualTo("PENDING_PAYMENT");
    }

    private String paymentState() { return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId); }

    private PaymentConfirmRequest request() { return new PaymentConfirmRequest(orderId, "pk-test", 10000); }
    private String body() { return "{\"orderId\":\"" + orderId + "\",\"paymentKey\":\"pk-test\",\"amount\":10000}"; }
    private String state() { return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId); }
    private OffsetDateTime confirmedAt() { return jdbc.queryForObject("SELECT confirmed_at FROM reservation WHERE id = ?", OffsetDateTime.class, reservationId); }
    private boolean fenced() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT approval_started_at IS NOT NULL FROM reservation WHERE id = ?", Boolean.class, reservationId)); }
    private int historyCount() { return jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id = ?", Integer.class, paymentId); }

    private TossPaymentResponse response(String status) {
        return new TossPaymentResponse("pk-test", orderId, "예약금", status, "카드", 10000, 10000,
                NOW, new TossPaymentResponse.Receipt("https://receipt"), null);
    }

    private void assertFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
