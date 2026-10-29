package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.dto.RefundResult;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.entity.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

// 트랜잭션 없음 (서비스가 트랜잭션 밖 호출만 허용), UUID 데이터는 매 테스트 후 정리
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(FakeTossPaymentsClient.Config.class)
class PaymentRefundServiceTest {

    private static final String REASON = "고객 요청";

    @Autowired PaymentRefundService service;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @SpyBean Clock clock;

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
        Long paymentId = approved();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제 승인·환불은 트랜잭션 밖에서 호출");
        assertThat(toss.cancelKeys()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_refund WHERE payment_id = ?", Long.class,
                paymentId)).isZero();
    }

    @Test
    @DisplayName("같은 키 재요청은 Toss를 다시 부르지 않고 최초 결과를 반환한다")
    void sameKeyReturnsFirstResult() throws Exception {
        doReturn(Instant.parse("2026-10-28T07:27:31.123456789Z")).when(clock).instant();
        Long paymentId = approved();

        RefundResult first = service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT);
        // 재시도 시 정책 한도가 바뀌어도 최초 결과
        RefundResult repeated = service.refund(customerId, paymentId, "key-1", REASON, 3_000L, 0);

        assertThat(first.refundStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(first.refundAmount()).isEqualTo(3_000);
        assertThat(first.totalRefundedAmount()).isEqualTo(3_000);
        assertThat(first.remainingAmount()).isEqualTo(7_000);
        assertThat(first.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(first.currency()).isEqualTo("KRW");
        assertThat(first.refundedAt()).isEqualTo(OffsetDateTime.parse("2026-10-28T07:27:31.123456Z"));
        OffsetDateTime stored = jdbc.queryForObject("SELECT completed_at FROM payment_refund WHERE payment_id = ?",
                (row, index) -> row.getObject(1, OffsetDateTime.class), paymentId);
        assertThat(stored).isAtSameInstantAs(first.refundedAt());
        OffsetDateTime requested = jdbc.queryForObject("SELECT requested_at FROM payment_refund WHERE payment_id = ?",
                (row, index) -> row.getObject(1, OffsetDateTime.class), paymentId);
        assertThat(requested).isAtSameInstantAs(first.refundedAt());
        assertSameResult(repeated, first);
        assertThat(mapper.writeValueAsString(repeated)).isEqualTo(mapper.writeValueAsString(first));
        assertThat(toss.cancelKeys()).containsExactly("key-1");
    }

    @Test
    @DisplayName("같은 키에 다른 금액이나 사유는 IDEMPOTENCY_CONFLICT다")
    void sameKeyWithDifferentRequestConflicts() {
        Long paymentId = approved();
        service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT);

        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, 4_000L, AMOUNT),
                ErrorCode.IDEMPOTENCY_CONFLICT);
        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, null, AMOUNT),
                ErrorCode.IDEMPOTENCY_CONFLICT);
        assertFailure(() -> service.refund(customerId, paymentId, "key-1", "다른 사유", 3_000L, AMOUNT),
                ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(toss.cancelKeys()).hasSize(1);
    }

    @Test
    @DisplayName("멱등키·사유가 없거나 금액이 양수가 아니면 INVALID_INPUT이다")
    void invalidInputIsRejected() {
        Long paymentId = approved();

        assertFailure(() -> service.refund(customerId, paymentId, null, REASON, null, AMOUNT), ErrorCode.INVALID_INPUT);
        assertFailure(() -> service.refund(customerId, paymentId, " ", REASON, null, AMOUNT), ErrorCode.INVALID_INPUT);
        assertFailure(() -> service.refund(customerId, paymentId, "k".repeat(301), REASON, null, AMOUNT),
                ErrorCode.INVALID_INPUT);
        assertFailure(() -> service.refund(customerId, paymentId, "key-1", " ", null, AMOUNT), ErrorCode.INVALID_INPUT);
        assertFailure(() -> service.refund(customerId, paymentId, "key-1", "r".repeat(501), null, AMOUNT),
                ErrorCode.INVALID_INPUT);
        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, 0L, AMOUNT),
                ErrorCode.INVALID_INPUT);
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test
    @DisplayName("금액을 생략하면 정책 한도 안의 허용액 전부를 환불한다")
    void omittedAmountRefundsAllowedAmount() {
        Long paymentId = approved(2_000);

        RefundResult result = service.refund(customerId, paymentId, "key-1", REASON, null, 5_000);

        assertThat(result.refundAmount()).isEqualTo(3_000);
        assertThat(result.totalRefundedAmount()).isEqualTo(5_000);
        assertThat(paymentRefunded(paymentId)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("허용액을 넘는 금액은 REFUND_AMOUNT_EXCEEDED이고 Toss를 부르지 않는다")
    void amountOverAllowedIsRejected() {
        Long paymentId = approved(2_000);

        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, 3_001L, 5_000),
                ErrorCode.REFUND_AMOUNT_EXCEEDED);
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test
    @DisplayName("정책상 0원이고 금액을 생략하면 Toss 호출 없이 NOT_REQUIRED다")
    void zeroRefundIsNotRequired() {
        Long paymentId = approved();

        RefundResult result = service.refund(customerId, paymentId, "key-1", REASON, null, 0);
        RefundResult repeated = service.refund(customerId, paymentId, "key-1", REASON, null, AMOUNT);

        assertThat(result.refundStatus()).isEqualTo(PaymentRefundStatus.NOT_REQUIRED);
        assertThat(result.refundAmount()).isZero();
        assertThat(result.refundedAt()).isNull();
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        assertSameResult(repeated, result);
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test
    @DisplayName("환불 결과 불명이면 409, 다른 키는 거부하고 같은 키 재시도는 확정한다")
    void unknownRefundBlocksOtherKeysUntilSameKeyRetry() {
        Long paymentId = approved();
        toss.failNext(FakeTossPaymentsClient.timeout());

        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT),
                ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        assertThat(refundStatus(paymentId, "key-1")).isEqualTo("UNKNOWN");
        assertThat(paymentRefunded(paymentId)).isZero();

        assertFailure(() -> service.refund(customerId, paymentId, "key-2", REASON, 3_000L, AMOUNT),
                ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);

        RefundResult retried = service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT);

        assertThat(retried.refundStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(toss.cancelKeys()).containsExactly("key-1", "key-1");
        assertThat(toss.cancelExecutions()).isEqualTo(1);
    }

    @Test
    @DisplayName("Toss가 환불을 거절하면 PAYMENT_REFUND_FAILED이고 같은 키 재요청도 같다")
    void rejectedRefundFails() {
        Long paymentId = approved();
        toss.failNext(FakeTossPaymentsClient.rejected());

        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT),
                ErrorCode.PAYMENT_REFUND_FAILED);
        assertThat(refundStatus(paymentId, "key-1")).isEqualTo("FAILED");
        assertThat(paymentRefunded(paymentId)).isZero();

        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT),
                ErrorCode.PAYMENT_REFUND_FAILED);
        assertThat(toss.cancelKeys()).hasSize(1);
    }

    @Test
    @DisplayName("다른 키로 부분 환불을 두 번 하면 누적액이 맞고 전액이면 REFUNDED다")
    void twoPartialRefundsAccumulate() {
        Long paymentId = approved();

        RefundResult first = service.refund(customerId, paymentId, "key-1", REASON, 4_000L, AMOUNT);
        RefundResult second = service.refund(customerId, paymentId, "key-2", REASON, null, AMOUNT);

        assertThat(first.totalRefundedAmount()).isEqualTo(4_000);
        assertThat(second.refundAmount()).isEqualTo(6_000);
        assertThat(second.totalRefundedAmount()).isEqualTo(AMOUNT);
        assertThat(second.remainingAmount()).isZero();
        assertThat(second.paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentRefunded(paymentId)).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("승인 전·실패·전액 환불 결제는 PAYMENT_NOT_REFUNDABLE이다")
    void notRefundableStatuses() {
        for (String status : new String[]{"READY", "FAILED"}) {
            Long paymentId = data.payment(customerId, data.hold(customerId, storeId, slot++), status, 0);
            assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, null, AMOUNT),
                    ErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
        Long refunded = data.payment(customerId, data.hold(customerId, storeId, slot++), "REFUNDED", AMOUNT);
        assertFailure(() -> service.refund(customerId, refunded, "key-1", REASON, null, AMOUNT),
                ErrorCode.PAYMENT_NOT_REFUNDABLE);
        assertThat(toss.cancelKeys()).isEmpty();
    }

    @Test
    @DisplayName("남의 결제나 없는 결제는 PAYMENT_NOT_FOUND다")
    void otherCustomersPaymentIsNotFound() {
        Long other = member("CUSTOMER");
        Long paymentId = data.payment(other, data.hold(other, storeId, slot++), "DONE", 0);

        assertFailure(() -> service.refund(customerId, paymentId, "key-1", REASON, null, AMOUNT),
                ErrorCode.PAYMENT_NOT_FOUND);
        assertFailure(() -> service.refund(customerId, -1L, "key-1", REASON, null, AMOUNT),
                ErrorCode.PAYMENT_NOT_FOUND);
    }

    private Long member(String type) {
        Long memberId = data.member(type);
        members.add(memberId);
        return memberId;
    }

    // DB에서 다시 읽은 시각은 오프셋이 다를 수 있어 시점으로 비교
    private static void assertSameResult(RefundResult actual, RefundResult expected) {
        assertThat(actual).usingRecursiveComparison().ignoringFields("refundedAt").isEqualTo(expected);
        if (expected.refundedAt() == null) {
            assertThat(actual.refundedAt()).isNull();
        } else {
            assertThat(actual.refundedAt()).isAtSameInstantAs(expected.refundedAt());
        }
    }

    private Long approved() {
        return approved(0);
    }

    // 승인된 결제 + 가짜 Toss 등록
    private Long approved(long refundedAmount) {
        String status = refundedAmount == 0 ? "DONE" : "PARTIAL_REFUNDED";
        Long paymentId = data.payment(customerId, data.hold(customerId, storeId, slot++), status, refundedAmount);
        toss.register(data.paymentKey(paymentId), data.orderId(paymentId), AMOUNT, refundedAmount);
        return paymentId;
    }

    private long paymentRefunded(Long paymentId) {
        return jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, paymentId);
    }

    private String refundStatus(Long paymentId, String key) {
        return jdbc.queryForObject("SELECT status FROM payment_refund WHERE payment_id = ? AND idempotency_key = ?",
                String.class, paymentId, key);
    }

    private static void assertFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
