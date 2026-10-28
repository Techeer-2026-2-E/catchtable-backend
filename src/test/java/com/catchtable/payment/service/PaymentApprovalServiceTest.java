package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.repository.PaymentStatusHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 트랜잭션 없음 (서비스가 트랜잭션 밖 호출만 허용), UUID 데이터는 매 테스트 후 정리
@SpringBootTest
@Import(FakeTossPaymentsClient.Config.class)
class PaymentApprovalServiceTest {

    @Autowired PaymentApprovalService service;
    @Autowired PaymentStatusHistoryRepository historyRepository;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> members = new ArrayList<>();
    private PaymentTestData data;
    private Long customerId;
    private Long storeId;
    private int slot;

    @BeforeEach
    void setUp() {
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
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> service.approve(customerId, orderId, "pk-1", AMOUNT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제 승인·환불은 트랜잭션 밖에서 호출");
        assertThat(toss.confirmKeys()).isEmpty();
        assertThat(status(paymentId)).isEqualTo("READY");
    }

    @Test
    @DisplayName("승인 재요청은 Toss를 다시 부르지 않고 같은 결과를 반환한다")
    void repeatedApprovalCallsTossOnce() {
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);

        var first = service.approve(customerId, orderId, "pk-1", AMOUNT);
        var repeated = service.approve(customerId, orderId, "pk-1", AMOUNT);

        assertThat(first.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(repeated).usingRecursiveComparison().ignoringFields("approvedAt").isEqualTo(first);
        assertThat(repeated.approvedAt()).isAtSameInstantAs(first.approvedAt());
        assertThat(toss.confirmKeys()).containsExactly("confirm-" + orderId);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).hasSize(1);
    }

    @Test
    @DisplayName("승인된 결제에 다른 paymentKey나 다른 금액은 거부한다")
    void approvedPaymentRejectsDifferentKeyOrAmount() {
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);
        service.approve(customerId, orderId, "pk-1", AMOUNT);

        assertFailure(() -> service.approve(customerId, orderId, "pk-2", AMOUNT), ErrorCode.PAYMENT_MISMATCH);
        assertFailure(() -> service.approve(customerId, orderId, "pk-1", AMOUNT - 1), ErrorCode.PAYMENT_MISMATCH);
        assertThat(toss.confirmKeys()).hasSize(1);
    }

    @Test
    @DisplayName("다른 결제에 이미 쓰인 paymentKey는 Toss 호출 없이 거부한다")
    void paymentKeyUsedByOtherPaymentIsRejected() {
        Long used = payment("DONE");
        Long paymentId = payment("READY");

        assertFailure(() -> service.approve(customerId, data.orderId(paymentId), data.paymentKey(used), AMOUNT),
                ErrorCode.PAYMENT_KEY_ALREADY_USED);
        assertThat(toss.confirmKeys()).isEmpty();
        assertThat(status(paymentId)).isEqualTo("READY");
    }

    @Test
    @DisplayName("승인 결과 불명이면 TIMEOUT으로 저장하고 409, 같은 요청 재시도는 같은 키로 승인한다")
    void timeoutIsSavedAndRetryUsesSameKey() {
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);
        toss.failNext(FakeTossPaymentsClient.timeout());

        assertFailure(() -> service.approve(customerId, orderId, "pk-1", AMOUNT),
                ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        assertThat(status(paymentId)).isEqualTo("TIMEOUT");

        var retried = service.approve(customerId, orderId, "pk-1", AMOUNT);

        assertThat(retried.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(toss.confirmKeys()).containsExactly("confirm-" + orderId, "confirm-" + orderId);
        assertThat(historyRepository.findAllByPaymentIdOrderByIdAsc(paymentId)).hasSize(2);
    }

    @Test
    @DisplayName("Toss가 승인을 거절하면 FAILED로 응답하고, 재요청은 INVALID_PAYMENT_STATUS다")
    void rejectedApprovalFails() {
        Long paymentId = payment("READY");
        String orderId = data.orderId(paymentId);
        toss.failNext(FakeTossPaymentsClient.rejected());

        var failed = service.approve(customerId, orderId, "pk-1", AMOUNT);

        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment WHERE id = ?", String.class, paymentId))
                .isEqualTo("REJECT_CARD_PAYMENT");
        assertFailure(() -> service.approve(customerId, orderId, "pk-1", AMOUNT), ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(toss.confirmKeys()).hasSize(1);
    }

    @Test
    @DisplayName("남의 주문이나 없는 주문은 PAYMENT_NOT_FOUND다")
    void otherCustomersOrderIsNotFound() {
        Long other = member("CUSTOMER");
        Long paymentId = data.payment(other, data.hold(other, storeId, slot++), "READY", 0);

        assertFailure(() -> service.approve(customerId, data.orderId(paymentId), "pk-1", AMOUNT),
                ErrorCode.PAYMENT_NOT_FOUND);
        assertFailure(() -> service.approve(customerId, "no-order", "pk-1", AMOUNT), ErrorCode.PAYMENT_NOT_FOUND);
    }

    private Long member(String type) {
        Long memberId = data.member(type);
        members.add(memberId);
        return memberId;
    }

    private Long payment(String status) {
        return data.payment(customerId, data.hold(customerId, storeId, slot++), status, 0);
    }

    private String status(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    private static void assertFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
