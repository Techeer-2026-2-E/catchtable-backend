package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.entity.PaymentStatusHistory;
import com.catchtable.payment.entity.PaymentStatusSource;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.repository.PaymentStatusHistoryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentServiceTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2030-01-19T12:00:00+09:00");

    @Autowired PaymentService service;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentStatusHistoryRepository historyRepository;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private PaymentTestData data;
    private Long customerId;
    private Long storeId;
    private int slot;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        customerId = data.member("CUSTOMER");
        storeId = data.store(data.member("OWNER"));
    }

    @Test
    void approvalRecordsOneHistoryAndRepeatRecordsNone() {
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);

        service.applyTossPayment(paymentId, toss(orderId, "DONE", AMOUNT), PaymentStatusSource.TOSS_CONFIRM);
        var repeated = service.applyTossPayment(paymentId, toss(orderId, "DONE", AMOUNT), PaymentStatusSource.TOSS_QUERY);

        assertThat(repeated.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(repeated.reservationId()).isEqualTo(repeated.holdId());
        Payment payment = reload(paymentId);
        assertThat(payment.getPaymentKey()).isEqualTo("pk-" + orderId);
        assertThat(payment.getApprovedAt().toInstant()).isEqualTo(APPROVED_AT.toInstant());
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(PaymentStatus.READY);
                    assertThat(history.getStatus()).isEqualTo(PaymentStatus.DONE);
                    assertThat(history.getSource()).isEqualTo(PaymentStatusSource.TOSS_CONFIRM);
                });
    }

    @Test
    void eachRefundRecordsOneHistoryAndRepeatRecordsNone() {
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);
        service.applyTossPayment(paymentId, toss(orderId, "DONE", AMOUNT), PaymentStatusSource.TOSS_CONFIRM);

        service.applyTossPayment(paymentId, toss(orderId, "PARTIAL_CANCELED", 7_000), PaymentStatusSource.TOSS_CANCEL);
        service.applyTossPayment(paymentId, toss(orderId, "PARTIAL_CANCELED", 7_000), PaymentStatusSource.TOSS_QUERY);
        var refunded = service.applyTossPayment(paymentId, toss(orderId, "CANCELED", 0), PaymentStatusSource.TOSS_CANCEL);

        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.canceledAmount()).isEqualTo(AMOUNT);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId))
                .extracting(PaymentStatusHistory::getStatus)
                .containsExactly(PaymentStatus.DONE, PaymentStatus.PARTIAL_REFUNDED, PaymentStatus.REFUNDED);
    }

    @Test
    void timeoutPaymentRefundedAtTossRecordsApprovalThenRefund() {
        Long paymentId = payment("TIMEOUT");

        var result = service.applyTossPayment(paymentId, toss(data.orderId(paymentId), "PARTIAL_CANCELED", 4_000),
                PaymentStatusSource.TOSS_QUERY);

        assertThat(result.status()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(result.canceledAmount()).isEqualTo(6_000);
        List<PaymentStatusHistory> histories = historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId);
        assertThat(histories).extracting(PaymentStatusHistory::getPreviousStatus)
                .containsExactly(PaymentStatus.TIMEOUT, PaymentStatus.DONE);
        assertThat(histories).extracting(PaymentStatusHistory::getStatus)
                .containsExactly(PaymentStatus.DONE, PaymentStatus.PARTIAL_REFUNDED);
    }

    @Test
    void abortedFailsAndExpiredCancelsOnce() {
        Long aborted = payment("READY");
        Long expired = payment("TIMEOUT");
        TossPaymentResponse abortedToss = new TossPaymentResponse(null, data.orderId(aborted), "예약금", "ABORTED",
                null, AMOUNT, AMOUNT, null, null, new TossPaymentResponse.Failure("REJECT_CARD_PAYMENT", "한도 초과"));

        service.applyTossPayment(aborted, abortedToss, PaymentStatusSource.TOSS_QUERY);
        service.applyTossPayment(aborted, abortedToss, PaymentStatusSource.TOSS_QUERY);
        service.applyTossPayment(expired, toss(data.orderId(expired), "EXPIRED", AMOUNT), PaymentStatusSource.TOSS_QUERY);
        service.applyTossPayment(expired, toss(data.orderId(expired), "EXPIRED", AMOUNT), PaymentStatusSource.TOSS_QUERY);

        assertThat(reload(aborted).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload(aborted).getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(reload(expired).getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(aborted)).hasSize(1);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(expired)).hasSize(1);
    }

    @Test
    void tossReadyKeepsCurrentState() {
        Long paymentId = payment("TIMEOUT");
        service.applyTossPayment(paymentId, toss(data.orderId(paymentId), "IN_PROGRESS", AMOUNT),
                PaymentStatusSource.TOSS_QUERY);
        assertThat(reload(paymentId).getStatus()).isEqualTo(PaymentStatus.TIMEOUT);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).isEmpty();
    }

    @Test
    void mismatchedOrderOrAmountIsRejectedWithoutChange() {
        Long paymentId = payment("READY");
        assertBusinessFailure(() -> service.applyTossPayment(paymentId,
                toss(data.orderId(paymentId), "DONE", AMOUNT, AMOUNT - 1), PaymentStatusSource.TOSS_CONFIRM),
                ErrorCode.PAYMENT_MISMATCH);
        assertBusinessFailure(() -> service.applyTossPayment(paymentId,
                toss("other-order", "DONE", AMOUNT), PaymentStatusSource.TOSS_CONFIRM), ErrorCode.PAYMENT_MISMATCH);
        assertThat(reload(paymentId).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).isEmpty();
    }

    @Test
    void contradictingTossResultIsRejected() {
        Long paymentId = payment("FAILED");
        assertBusinessFailure(() -> service.applyTossPayment(paymentId,
                toss(data.orderId(paymentId), "DONE", AMOUNT), PaymentStatusSource.TOSS_QUERY),
                ErrorCode.INVALID_PAYMENT_STATUS);
        assertBusinessFailure(() -> service.applyTossPayment(-1L,
                toss("order", "DONE", AMOUNT), PaymentStatusSource.TOSS_QUERY), ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void markFailedRecordsOneHistoryAndRepeatRecordsNone() {
        Long paymentId = payment("READY");

        service.markFailed(paymentId, "REJECT_CARD_PAYMENT", "한도 초과", PaymentStatusSource.TOSS_CONFIRM);
        var repeated = service.markFailed(paymentId, "REJECT_CARD_PAYMENT", "한도 초과",
                PaymentStatusSource.TOSS_CONFIRM);

        assertThat(repeated.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload(paymentId).getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(PaymentStatus.READY);
                    assertThat(history.getStatus()).isEqualTo(PaymentStatus.FAILED);
                    assertThat(history.getSource()).isEqualTo(PaymentStatusSource.TOSS_CONFIRM);
                });
    }

    @Test
    void markTimeoutRecordsOneHistoryAndRepeatRecordsNone() {
        Long paymentId = payment("READY");

        service.markTimeout(paymentId, "승인 응답 시간초과", PaymentStatusSource.TOSS_CONFIRM);
        var repeated = service.markTimeout(paymentId, "승인 응답 시간초과", PaymentStatusSource.TOSS_CONFIRM);

        assertThat(repeated.status()).isEqualTo(PaymentStatus.TIMEOUT);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(PaymentStatus.READY);
                    assertThat(history.getStatus()).isEqualTo(PaymentStatus.TIMEOUT);
                });
    }

    @Test
    void markFailedOnApprovedPaymentIsRejected() {
        Long paymentId = payment("READY");
        service.applyTossPayment(paymentId, toss(data.orderId(paymentId), "DONE", AMOUNT),
                PaymentStatusSource.TOSS_CONFIRM);

        assertBusinessFailure(() -> service.markFailed(paymentId, "REJECT_CARD_PAYMENT", "한도 초과",
                PaymentStatusSource.TOSS_CONFIRM), ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(reload(paymentId).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).hasSize(1);
    }

    private Long payment(String status) {
        return data.payment(customerId, data.hold(customerId, storeId, slot++), status, 0);
    }

    private Payment reload(Long paymentId) {
        entityManager.flush();
        entityManager.clear();
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private static TossPaymentResponse toss(String orderId, String status, long balanceAmount) {
        return toss(orderId, status, balanceAmount, AMOUNT);
    }

    private static TossPaymentResponse toss(String orderId, String status, long balanceAmount, long totalAmount) {
        return new TossPaymentResponse("pk-" + orderId, orderId, "예약금", status, "카드", totalAmount,
                balanceAmount, APPROVED_AT, new TossPaymentResponse.Receipt("https://receipt"), null);
    }

    private static void assertBusinessFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
