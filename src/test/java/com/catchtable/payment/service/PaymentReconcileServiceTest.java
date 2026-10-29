package com.catchtable.payment.service;

import com.catchtable.payment.ConfirmedEventCounter;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.reservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// 트랜잭션 없음 (단계별 실제 커밋), 고정 Clock, UUID 데이터는 매 테스트 후 정리
@SpringBootTest
@AutoConfigureMockMvc
@Import({FakeTossPaymentsClient.Config.class, ConfirmedEventCounter.Config.class})
class PaymentReconcileServiceTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");

    @Autowired PaymentReconcileService service;
    @Autowired ReservationService reservationService;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired ConfirmedEventCounter events;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;
    @MockBean Clock clock;

    private final AtomicReference<OffsetDateTime> now = new AtomicReference<>();
    private final List<Long> members = new ArrayList<>();
    private PaymentTestData data;
    private Long customerId;
    private Long storeId;
    private int slot;

    @BeforeEach
    void setUp() {
        now.set(NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenAnswer(call -> now.get().toInstant());
        toss.reset();
        data = new PaymentTestData(jdbc);
        customerId = member("CUSTOMER");
        storeId = data.store(member("OWNER"));
    }

    @AfterEach
    void cleanUp() {
        data.deleteStore(storeId, members);
    }

    @Test
    @DisplayName("트랜잭션 안에서 호출하면 Toss를 부르지 않고 예외다")
    void rejectsCallInsideTransaction() {
        Long paymentId = protectedPayment("TIMEOUT", "pk-1");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> service.reconcile(paymentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제 재확인은 트랜잭션 밖에서 호출");
        assertThat(toss.queries()).isZero();
    }

    @Test
    @DisplayName("결과 불명 결제가 Toss에서 승인됐고 선점이 보호돼 있으면 결제 DONE·예약 확정, 반복해도 이력·알림 1회")
    void timeoutWithProtectedHoldConfirmsOnce() {
        Long paymentId = protectedPayment("TIMEOUT", "pk-1");
        toss.query(data.orderId(paymentId), "pk-1", "DONE", AMOUNT);

        service.reconcile(paymentId);
        service.reconcile(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo("DONE");
        assertThat(reservationStatus(paymentId)).isEqualTo("CONFIRMED");
        assertThat(histories(paymentId)).isEqualTo(1);
        assertThat(events.count(reservationId(paymentId))).isEqualTo(1);
        // 최종 상태 뒤에는 Toss를 다시 부르지 않음
        assertThat(toss.queries()).isEqualTo(1);
        assertThat(refundRequired(paymentId)).isFalse();
    }

    @ParameterizedTest(name = "Toss {0} → {1}")
    @CsvSource({"ABORTED, FAILED", "EXPIRED, CANCELED"})
    @DisplayName("결과 불명 결제가 Toss에서 실패·만료면 실패로 확정하고 선점을 반환한다")
    void failedResultReleasesHold(String tossStatus, String expected) {
        Long paymentId = protectedPayment("TIMEOUT", "pk-1");
        toss.query(data.orderId(paymentId), null, tossStatus, AMOUNT);

        service.reconcile(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo(expected);
        assertThat(reservationStatus(paymentId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT approval_payment_id FROM reservation WHERE id = ?", Long.class,
                reservationId(paymentId))).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"READY", "TIMEOUT"})
    @DisplayName("Toss도 승인 전이면 결제·선점을 그대로 두고, 선점 기한이 지나도 만료하지 않는다")
    void unresolvedPaymentKeepsHold(String status) {
        Long paymentId = protectedPayment(status, "pk-1");
        toss.query(data.orderId(paymentId), null, "IN_PROGRESS", AMOUNT);
        now.set(NOW.plusMinutes(30));

        service.reconcile(paymentId);
        reservationService.expireHolds();

        assertThat(paymentStatus(paymentId)).isEqualTo(status);
        assertThat(reservationStatus(paymentId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("보호 없는 늦은 승인은 예약을 확정하지 않고 자리를 반환하며 환불 필요로 표시한다 (반복해도 이력 1건)")
    void unprotectedLateSuccessRequiresRefund() {
        Long reservationId = data.hold(customerId, storeId, slot++);
        Long paymentId = data.payment(customerId, reservationId, "TIMEOUT", 0);
        toss.query(data.orderId(paymentId), "pk-late", "DONE", AMOUNT);

        service.reconcile(paymentId);
        service.reconcile(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo("DONE");
        assertThat(reservationStatus(paymentId)).isEqualTo("EXPIRED");
        assertThat(refundRequired(paymentId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment_status_history WHERE payment_id = ? AND detail LIKE '환불 필요%'
                """, Long.class, paymentId)).isEqualTo(1);
        assertThat(events.count(reservationId)).isZero();
    }

    @ParameterizedTest(name = "Toss {0}")
    @CsvSource({"ABORTED, FAILED, false", "DONE, DONE, true"})
    @DisplayName("격리 예약은 Toss 최종 결과로 격리를 풀고 자리를 반환하며, 승인이면 환불 필요로 표시한다")
    void quarantinedReservationIsResolved(String tossStatus, String expected, boolean refundRequired) {
        Long reservationId = data.hold(customerId, storeId, slot++);
        Long paymentId = data.payment(customerId, reservationId, "TIMEOUT", 0);
        jdbc.update("UPDATE reservation SET approval_quarantined = TRUE WHERE id = ?", reservationId);
        toss.query(data.orderId(paymentId), "pk-q", tossStatus, AMOUNT);

        service.reconcile(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo(expected);
        assertThat(reservationStatus(paymentId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT approval_quarantined FROM reservation WHERE id = ?", Boolean.class,
                reservationId)).isFalse();
        assertThat(refundRequired(paymentId)).isEqualTo(refundRequired);
    }

    @Test
    @DisplayName("승인 시작 15일이 지난 미확정 건은 재승인 없이 조회 결과로만 정리한다")
    void staleApprovalIsResolvedWithoutReapproval() {
        Long reservationId = data.hold(customerId, storeId, slot++);
        Long paymentId = data.payment(customerId, reservationId, "TIMEOUT", 0);
        data.protect(reservationId, paymentId, "pk-old", NOW.minusDays(16));
        toss.query(data.orderId(paymentId), null, "EXPIRED", AMOUNT);

        service.reconcile(paymentId);

        assertThat(toss.confirmKeys()).isEmpty();
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELED");
        assertThat(reservationStatus(paymentId)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("Toss 조회가 실패하면 결제·선점 상태를 유지한다")
    void queryFailureKeepsState() {
        Long paymentId = protectedPayment("TIMEOUT", "pk-1");
        toss.failNext(FakeTossPaymentsClient.timeout());

        service.reconcile(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo("TIMEOUT");
        assertThat(reservationStatus(paymentId)).isEqualTo("PENDING_PAYMENT");
        assertThat(histories(paymentId)).isZero();
    }

    @Test
    @DisplayName("결과 불명 환불이 Toss에 반영돼 있으면 Toss 취소 없이 COMPLETED로 확정한다")
    void reflectedUnknownRefundCompletes() {
        Long paymentId = approvedPayment(3_000);
        Long refundId = data.inFlightRefund(paymentId, "key-1", 3_000, "UNKNOWN", NOW.minusHours(1));

        service.reconcile(paymentId);

        assertThat(refundStatus(refundId)).isEqualTo("COMPLETED");
        assertThat(paymentStatus(paymentId)).isEqualTo("PARTIAL_REFUNDED");
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test
    @DisplayName("결과 불명 환불이 Toss에 없으면 15일 안에는 같은 키로 재시도한다")
    void missingUnknownRefundRetriesSameKey() {
        Long paymentId = approvedPayment(0);
        Long refundId = data.inFlightRefund(paymentId, "key-1", 3_000, "UNKNOWN", NOW.minusDays(14));

        service.reconcile(paymentId);

        assertThat(toss.cancelKeys()).containsExactly("key-1");
        assertThat(refundStatus(refundId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, paymentId))
                .isEqualTo(3_000);
    }

    @Test
    @DisplayName("멱등키 15일이 지난 미반영 환불은 재시도 없이 FAILED로 둔다")
    void expiredUnknownRefundFails() {
        Long paymentId = approvedPayment(0);
        Long refundId = data.inFlightRefund(paymentId, "key-1", 3_000, "REQUESTED", NOW.minusDays(15));

        service.reconcile(paymentId);

        assertThat(toss.cancelKeys()).isEmpty();
        assertThat(refundStatus(refundId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment_refund WHERE id = ?", String.class,
                refundId)).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
    }

    @Test
    @DisplayName("자동 재확인은 대상만 처리하고 한 건이 실패해도 나머지를 처리한다")
    void batchContinuesAfterFailure() {
        Long broken = protectedPayment("TIMEOUT", "pk-1");
        Long paymentId = protectedPayment("TIMEOUT", "pk-2");
        // 금액 위변조 응답 → PAYMENT_MISMATCH
        toss.query(data.orderId(broken), "pk-1", "DONE", AMOUNT + 1);
        toss.query(data.orderId(paymentId), "pk-2", "DONE", AMOUNT);

        service.reconcileTargets(1_000);

        assertThat(paymentStatus(broken)).isEqualTo("TIMEOUT");
        assertThat(paymentStatus(paymentId)).isEqualTo("DONE");
        assertThat(reservationStatus(paymentId)).isEqualTo("CONFIRMED");
    }

    private Long member(String type) {
        Long memberId = data.member(type);
        members.add(memberId);
        return memberId;
    }

    // 승인 시작이 기록된 선점과 결제
    private Long protectedPayment(String status, String paymentKey) {
        Long reservationId = data.hold(customerId, storeId, slot++);
        Long paymentId = data.payment(customerId, reservationId, status, 0);
        data.protect(reservationId, paymentId, paymentKey, NOW.minusMinutes(1));
        return paymentId;
    }

    // 확정 예약의 승인 결제 (Toss 누적 취소액 지정)
    private Long approvedPayment(long tossCanceledAmount) {
        Long reservationId = data.hold(customerId, storeId, slot++);
        Long paymentId = data.payment(customerId, reservationId, "DONE", 0);
        jdbc.update("UPDATE reservation SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?", NOW, reservationId);
        toss.register(data.paymentKey(paymentId), data.orderId(paymentId), AMOUNT, tossCanceledAmount);
        return paymentId;
    }

    private Long reservationId(Long paymentId) {
        return jdbc.queryForObject("SELECT reservation_id FROM payment WHERE id = ?", Long.class, paymentId);
    }

    private String paymentStatus(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String reservationStatus(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class,
                reservationId(paymentId));
    }

    private String refundStatus(Long refundId) {
        return jdbc.queryForObject("SELECT status FROM payment_refund WHERE id = ?", String.class, refundId);
    }

    private boolean refundRequired(Long paymentId) {
        return jdbc.queryForObject("SELECT refund_required_at IS NOT NULL FROM payment WHERE id = ?", Boolean.class,
                paymentId);
    }

    private long histories(Long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id = ?", Long.class,
                paymentId);
    }
}
