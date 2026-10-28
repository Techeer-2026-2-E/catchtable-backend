package com.catchtable.payment.entity;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2030, 1, 10, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final PaymentStatusSource SOURCE = PaymentStatusSource.TOSS_QUERY;
    private static final long AMOUNT = 10_000;

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"READY", "TIMEOUT"})
    void approvesFromReadyOrTimeoutAndRecordsHistory(PaymentStatus from) {
        Payment payment = paymentIn(from);

        PaymentStatusHistory history = approve(payment, "pk-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getPaymentKey()).isEqualTo("pk-1");
        assertThat(payment.getMethod()).isEqualTo("카드");
        assertThat(payment.getApprovedAt()).isEqualTo(NOW);
        assertThat(payment.getReceiptUrl()).isEqualTo("https://receipt");
        assertThat(history.getPreviousStatus()).isEqualTo(from);
        assertThat(history.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(history.getSource()).isEqualTo(SOURCE);
        assertThat(history.getCreatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"READY", "TIMEOUT"})
    void failsOrCancelsBeforeApproval(PaymentStatus from) {
        Payment failed = paymentIn(from);
        assertThat(failed.fail("REJECT_CARD_PAYMENT", "한도 초과", SOURCE, NOW).getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(failed.getFailureMessage()).isEqualTo("한도 초과");

        Payment canceled = paymentIn(from);
        assertThat(canceled.cancel("결제창 이탈", PaymentStatusSource.USER, NOW).getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void readyCanBecomeTimeout() {
        Payment payment = paymentIn(PaymentStatus.READY);
        assertThat(payment.markTimeout("승인 응답 없음", PaymentStatusSource.SYSTEM, NOW).getStatus())
                .isEqualTo(PaymentStatus.TIMEOUT);
    }

    @Test
    void partialRefundsAccumulateUntilFullRefund() {
        Payment payment = paymentIn(PaymentStatus.DONE);

        PaymentStatusHistory first = payment.refund(3_000, "부분 취소", SOURCE, NOW);
        assertThat(first.getPreviousStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);

        PaymentStatusHistory second = payment.refund(5_000, "추가 취소", SOURCE, NOW);
        assertThat(second.getPreviousStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(5_000);

        assertThat(payment.refund(AMOUNT, "전액 취소", SOURCE, NOW).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(AMOUNT);
    }

    @Test
    void doneCanBeFullyRefundedAtOnce() {
        Payment payment = paymentIn(PaymentStatus.DONE);
        assertThat(payment.refund(AMOUNT, "전액 취소", SOURCE, NOW).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void repeatingTheSameRequestChangesNothing(PaymentStatus status) {
        Payment payment = paymentIn(status);
        String failureMessage = payment.getFailureMessage();
        long refundedAmount = payment.getRefundedAmount();
        PaymentStatusHistory repeated = switch (status) {
            case READY -> null;
            case DONE -> approve(payment, "pk-1");
            case FAILED -> payment.fail("REJECT_CARD_PAYMENT", "다른 메시지", SOURCE, NOW);
            case CANCELED -> payment.cancel("다른 사유", SOURCE, NOW);
            case TIMEOUT -> payment.markTimeout("다른 사유", SOURCE, NOW);
            case PARTIAL_REFUNDED -> payment.refund(3_000, "재요청", SOURCE, NOW);
            case REFUNDED -> payment.refund(AMOUNT, "재요청", SOURCE, NOW);
        };
        assertThat(repeated).isNull();
        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getFailureMessage()).isEqualTo(failureMessage);
        assertThat(payment.getRefundedAmount()).isEqualTo(refundedAmount);
    }

    @Test
    void approvedPaymentIgnoresLateApprovalWithTheSameKey() {
        Payment payment = paymentIn(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(approve(payment, "pk-1")).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
    }

    @ParameterizedTest
    @CsvSource({
            "READY, REFUND", "TIMEOUT, REFUND",
            "DONE, FAIL", "DONE, CANCEL", "DONE, TIMEOUT", "DONE, APPROVE_OTHER_KEY",
            "FAILED, APPROVE", "FAILED, CANCEL", "FAILED, TIMEOUT", "FAILED, REFUND",
            "CANCELED, APPROVE", "CANCELED, FAIL", "CANCELED, TIMEOUT", "CANCELED, REFUND",
            "PARTIAL_REFUNDED, FAIL", "PARTIAL_REFUNDED, CANCEL", "PARTIAL_REFUNDED, TIMEOUT",
            "PARTIAL_REFUNDED, APPROVE_OTHER_KEY",
            "REFUNDED, FAIL", "REFUNDED, CANCEL", "REFUNDED, TIMEOUT", "REFUNDED, APPROVE_OTHER_KEY"
    })
    void rejectsDisallowedTransitionsWithoutChange(PaymentStatus from, String action) {
        Payment payment = paymentIn(from);
        assertBusinessFailure(() -> {
            switch (action) {
                case "APPROVE" -> approve(payment, "pk-1");
                case "APPROVE_OTHER_KEY" -> approve(payment, "pk-other");
                case "FAIL" -> payment.fail("ERROR", "오류", SOURCE, NOW);
                case "CANCEL" -> payment.cancel("취소", SOURCE, NOW);
                case "TIMEOUT" -> payment.markTimeout("응답 없음", SOURCE, NOW);
                case "REFUND" -> payment.refund(1_000, "환불", SOURCE, NOW);
                default -> throw new IllegalArgumentException(action);
            }
        }, ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(payment.getStatus()).isEqualTo(from);
    }

    @Test
    void refundCannotExceedAmountOrDecrease() {
        Payment payment = paymentIn(PaymentStatus.DONE);
        assertBusinessFailure(() -> payment.refund(AMOUNT + 1, "초과", SOURCE, NOW), ErrorCode.REFUND_AMOUNT_EXCEEDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);

        payment.refund(5_000, "부분 취소", SOURCE, NOW);
        assertBusinessFailure(() -> payment.refund(AMOUNT + 1, "초과", SOURCE, NOW), ErrorCode.REFUND_AMOUNT_EXCEEDED);
        assertBusinessFailure(() -> payment.refund(4_000, "감소", SOURCE, NOW), ErrorCode.INVALID_INPUT);
        assertBusinessFailure(() -> payment.refund(0, "0원", SOURCE, NOW), ErrorCode.INVALID_INPUT);
        assertThat(payment.getRefundedAmount()).isEqualTo(5_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
    }

    @Test
    void readyRejectsInvalidOrder() {
        assertBusinessFailure(() -> Payment.ready(null, null, "order-1", "예약금", 0), ErrorCode.INVALID_INPUT);
        assertBusinessFailure(() -> Payment.ready(null, null, " ", "예약금", AMOUNT), ErrorCode.INVALID_INPUT);
        Payment payment = Payment.ready(null, null, "order-1", "예약금", AMOUNT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getRefundedAmount()).isZero();
        assertThat(payment.getPaymentKey()).isNull();
    }

    private static Payment paymentIn(PaymentStatus status) {
        Payment payment = Payment.ready(null, null, "order-1", "예약금", AMOUNT);
        switch (status) {
            case READY -> { }
            case DONE -> approve(payment, "pk-1");
            case FAILED -> payment.fail("REJECT_CARD_PAYMENT", "한도 초과", SOURCE, NOW);
            case CANCELED -> payment.cancel("결제창 이탈", SOURCE, NOW);
            case TIMEOUT -> payment.markTimeout("승인 응답 없음", SOURCE, NOW);
            case PARTIAL_REFUNDED -> {
                approve(payment, "pk-1");
                payment.refund(3_000, "부분 취소", SOURCE, NOW);
            }
            case REFUNDED -> {
                approve(payment, "pk-1");
                payment.refund(AMOUNT, "전액 취소", SOURCE, NOW);
            }
        }
        assertThat(payment.getStatus()).isEqualTo(status);
        return payment;
    }

    private static PaymentStatusHistory approve(Payment payment, String paymentKey) {
        return payment.approve(paymentKey, "카드", NOW, "https://receipt", SOURCE, NOW);
    }

    private static void assertBusinessFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
