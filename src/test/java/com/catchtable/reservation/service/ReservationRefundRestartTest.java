package com.catchtable.reservation.service;

import com.catchtable.CatchtableApplication;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.dto.RefundResult;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.catchtable.reservation.service.ReservationRefundExecutionTest.assertCode;
import static com.catchtable.global.exception.ErrorCode.PAYMENT_RECONCILIATION_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

// 실제 애플리케이션 컨텍스트와 연결 풀을 닫고 새 인스턴스를 시작한다. 복구 근거는 DB뿐이다.
class ReservationRefundRestartTest {
    static final OffsetDateTime FIRST = OffsetDateTime.parse("2030-01-19T23:59:00+09:00");
    static OffsetDateTime current;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(120)
    void immutableBasisAndRawOmittedAmountSurviveApplicationRestart(boolean cancellation) {
        current = FIRST;
        Long customer, owner, store, reservation, payment;
        Map<String, Object> basis, commonBasis;
        String order, paymentKey;
        try (var first = start()) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            var data = new PaymentTestData(jdbc);
            customer = data.member("CUSTOMER");
            owner = data.member("OWNER");
            store = data.store(owner);
            reservation = data.hold(customer, store, 0);
            jdbc.update("UPDATE reservation SET status = 'CONFIRMED', confirmed_at = ?, deposit_amount_snapshot = 10000, refund_policy_snapshot = 'FLEXIBLE' WHERE id = ?", FIRST.minusDays(1), reservation);
            payment = data.payment(customer, reservation, "DONE", 0);
            order = data.orderId(payment);
            paymentKey = data.paymentKey(payment);
            var fake = first.getBean(FakeTossPaymentsClient.class);
            fake.register(paymentKey, order, 10000, 0);
            fake.failNext(FakeTossPaymentsClient.timeout());
            var executions = first.getBean(ReservationRefundExecutionService.class);
            assertCode(() -> run(executions, cancellation, customer, reservation, payment), PAYMENT_RECONCILIATION_REQUIRED);
            basis = basis(jdbc, reservation);
            commonBasis = commonBasis(jdbc, payment);
            assertThat(basis.get("policy_refund_limit")).isEqualTo(10000L);
            assertThat(basis.get("requested_amount")).isNull();
        }

        current = FIRST.plusMinutes(2); // KST 자정 이후 재계산하면 50%가 되지만 최초 100%를 유지한다.
        RefundResult result;
        try (var second = start()) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            var fake = second.getBean(FakeTossPaymentsClient.class);
            fake.register(paymentKey, order, 10000, 0);
            var executions = second.getBean(ReservationRefundExecutionService.class);
            result = run(executions, cancellation, customer, reservation, payment);
            assertThat(basis(jdbc, reservation)).isEqualTo(basis);
            assertThat(commonBasis(jdbc, payment)).isEqualTo(commonBasis);
            assertThat(jdbc.queryForObject("SELECT refund_in_progress FROM reservation WHERE id = ?", Boolean.class, reservation)).isFalse();
            assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, payment)).isEqualTo(10000);
            assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation))
                    .isEqualTo(cancellation ? "CANCELLED" : "CONFIRMED");
            assertThat(fake.cancelExecutions()).isEqualTo(1);
        }

        current = FIRST.plusDays(2); // 완료 재시도는 최초 유효성 검사와 PG 호출을 다시 하지 않는다.
        try (var third = start()) {
            JdbcTemplate jdbc = third.getBean(JdbcTemplate.class);
            var fake = third.getBean(FakeTossPaymentsClient.class);
            try {
                var replay = run(third.getBean(ReservationRefundExecutionService.class), cancellation, customer, reservation, payment);
                assertThat(replay).isEqualTo(result);
                assertThat(fake.cancelKeys()).isEmpty();
                assertThat(fake.queries()).isZero();
                assertThat(basis(jdbc, reservation)).isEqualTo(basis);
            } finally {
                new PaymentTestData(jdbc).deleteStore(store, List.of(customer, owner));
            }
        }
    }

    private static RefundResult run(ReservationRefundExecutionService executions, boolean cancellation,
                                    Long customer, Long reservation, Long payment) {
        if (cancellation) {
            executions.cancelCustomer(customer, reservation, "restart-cancel");
            return null;
        }
        return executions.refund(customer, payment, "restart-money", "고객 요청", null);
    }

    private static Map<String, Object> basis(JdbcTemplate jdbc, Long id) {
        return jdbc.queryForMap("SELECT requested_at, reservation_start_at, policy_refund_limit, requested_amount, refund_amount FROM reservation_refund_request WHERE reservation_id = ?", id);
    }

    private static Map<String, Object> commonBasis(JdbcTemplate jdbc, Long id) {
        return jdbc.queryForMap("SELECT requested_at, idempotency_key, requested_amount, refund_amount FROM payment_refund WHERE payment_id = ?", id);
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(CatchtableApplication.class, RestartClock.class, FakeTossPaymentsClient.Config.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--spring.jmx.enabled=false");
    }

    @TestConfiguration
    static class RestartClock {
        @Bean @Primary Clock refundRestartClock() { return Clock.fixed(current.toInstant(), ZoneId.of("Asia/Seoul")); }
    }
}
