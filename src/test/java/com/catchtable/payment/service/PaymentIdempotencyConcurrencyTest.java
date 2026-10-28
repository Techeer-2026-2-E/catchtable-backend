package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 트랜잭션 없음 (요청마다 실제 커밋), UUID 데이터는 finally 정리
@SpringBootTest
@Import(FakeTossPaymentsClient.Config.class)
class PaymentIdempotencyConcurrencyTest {

    private static final String REASON = "고객 요청";

    @Autowired PaymentApprovalService approvalService;
    @Autowired PaymentRefundService refundService;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired JdbcTemplate jdbc;

    private PaymentTestData data;
    private Long customerId;
    private Long ownerId;
    private Long storeId;
    private int slot;

    @BeforeEach
    void setUp() {
        toss.reset();
        data = new PaymentTestData(jdbc);
        customerId = data.member("CUSTOMER");
        ownerId = data.member("OWNER");
        storeId = data.store(ownerId);
    }

    @AfterEach
    void cleanUp() {
        data.deleteStore(storeId, List.of(customerId, ownerId));
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 주문 승인 동시 요청은 DONE 1건과 이력 1건만 남긴다")
    void concurrentApprovalRecordsOnce() throws Exception {
        Long paymentId = payment("READY", 0);
        String orderId = data.orderId(paymentId);
        toss.delay(200);

        List<Object> outcomes = concurrently(() -> approvalService.approve(customerId, orderId, "pk-1", AMOUNT),
                () -> approvalService.approve(customerId, orderId, "pk-1", AMOUNT));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isNotInstanceOf(Throwable.class));
        assertThat(status(paymentId)).isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM payment_status_history WHERE payment_id = ?", paymentId))
                .isEqualTo(1);
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 키 환불 동시 요청은 Toss 환불을 한 번만 처리한다")
    void concurrentSameKeyRefundExecutesOnce() throws Exception {
        Long paymentId = approved();
        toss.delay(200);

        List<Object> outcomes = concurrently(
                () -> refundService.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT),
                () -> refundService.refund(customerId, paymentId, "key-1", REASON, 3_000L, AMOUNT));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isNotInstanceOf(Throwable.class));
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        assertThat(refunded(paymentId)).isEqualTo(3_000);
        assertThat(count("SELECT count(*) FROM payment_refund WHERE payment_id = ?", paymentId)).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    @DisplayName("다른 키 환불 동시 요청도 누적 환불액이 한도를 넘지 않는다")
    void concurrentDifferentKeyRefundsStayWithinLimit() throws Exception {
        Long paymentId = approved();
        toss.delay(200);

        List<Object> outcomes = concurrently(
                () -> refundService.refund(customerId, paymentId, "key-1", REASON, 7_000L, AMOUNT),
                () -> refundService.refund(customerId, paymentId, "key-2", REASON, 7_000L, AMOUNT));

        assertThat(outcomes).filteredOn(outcome -> !(outcome instanceof Throwable)).hasSize(1);
        assertThat(outcomes).filteredOn(Throwable.class::isInstance).singleElement()
                .satisfies(failure -> assertThat(((BusinessException) failure).getErrorCode())
                        .isIn(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED, ErrorCode.REFUND_AMOUNT_EXCEEDED));
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        assertThat(refunded(paymentId)).isEqualTo(7_000);
    }

    @Test
    @Timeout(20)
    @DisplayName("결과 불명 409 응답 뒤에도 TIMEOUT·UNKNOWN 기록은 커밋되어 있다")
    void unknownResultIsCommittedBeforeConflict() {
        Long readyId = payment("READY", 0);
        toss.failNext(FakeTossPaymentsClient.timeout());
        assertFailure(() -> approvalService.approve(customerId, data.orderId(readyId), "pk-1", AMOUNT));
        assertThat(status(readyId)).isEqualTo("TIMEOUT");

        Long approvedId = approved();
        toss.failNext(FakeTossPaymentsClient.timeout());
        assertFailure(() -> refundService.refund(customerId, approvedId, "key-1", REASON, 3_000L, AMOUNT));
        assertThat(jdbc.queryForObject("SELECT status FROM payment_refund WHERE payment_id = ?", String.class,
                approvedId)).isEqualTo("UNKNOWN");
    }

    private static void assertFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED));
    }

    // 동시에 시작, 결과 또는 예외
    @SafeVarargs
    private static List<Object> concurrently(Callable<Object>... tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Long payment(String status, long refundedAmount) {
        return data.payment(customerId, data.hold(customerId, storeId, slot++), status, refundedAmount);
    }

    private Long approved() {
        Long paymentId = payment("DONE", 0);
        toss.register(data.paymentKey(paymentId), data.orderId(paymentId), AMOUNT, 0);
        return paymentId;
    }

    private String status(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    private long refunded(Long paymentId) {
        return jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, paymentId);
    }

    private long count(String sql, Long paymentId) {
        return jdbc.queryForObject(sql, Long.class, paymentId);
    }
}
