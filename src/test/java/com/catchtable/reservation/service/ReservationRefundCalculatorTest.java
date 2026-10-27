package com.catchtable.reservation.service;

import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.RefundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationRefundCalculatorTest {

    private final ReservationRefundCalculator calculator = new ReservationRefundCalculator();
    private final OffsetDateTime start = OffsetDateTime.parse("2026-10-30T18:00:00+09:00");

    @ParameterizedTest
    @CsvSource({
            "FLEXIBLE, 0, 50", "FLEXIBLE, 1, 100", "FLEXIBLE, 7, 100",
            "STANDARD, 0, 0", "STANDARD, 1, 50", "STANDARD, 2, 100", "STANDARD, 7, 100",
            "STRICT, 0, 0", "STRICT, 1, 20", "STRICT, 2, 50", "STRICT, 3, 100", "STRICT, 7, 100"
    })
    void appliesTheSnapshotPolicyByLocalCalendarDate(RefundPolicy policy, int daysBefore, int rate) {
        var result = calculator.calculate(10_000, 0, policy, start,
                start.minusDays(daysBefore).minusHours(1), CancellationActor.CUSTOMER, false);

        assertThat(result.refundRate()).isEqualTo(rate);
        assertThat(result.refundAmount()).isEqualTo(100L * rate);
        assertThat(result.policyDescription()).contains(policy.getDescription(), "예약일 2026-10-30");
    }

    @Test
    void truncatesSubWonAmountsBeforeSubtractingPreviousRefunds() {
        var result = calculator.calculate(1_001, 100, RefundPolicy.FLEXIBLE, start,
                start.minusHours(1), CancellationActor.CUSTOMER, false);

        assertThat(result.refundAmount()).isEqualTo(400);
    }

    @Test
    void subtractsPreviousRefundsFromTheTotalEntitlement() {
        var result = calculator.calculate(10_000, 1_000, RefundPolicy.STANDARD, start,
                start.minusDays(1), CancellationActor.CUSTOMER, false);

        assertThat(result.refundRate()).isEqualTo(50);
        assertThat(result.refundAmount()).isEqualTo(4_000);
        assertThat(result.policyDescription()).contains("기환불액 1000원 차감");
        assertThat(calculator.calculate(10_000, 6_000, RefundPolicy.STANDARD, start,
                start.minusDays(1), CancellationActor.CUSTOMER, false).refundAmount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(RefundPolicy.class)
    void ownerCancellationRefundsTheRemainingPaymentEvenAfterTheVisitDate(RefundPolicy policy) {
        var result = calculator.calculate(10_000, 1_500, policy, start,
                start.plusDays(1), CancellationActor.OWNER, false);

        assertThat(result.refundRate()).isEqualTo(100);
        assertThat(result.refundAmount()).isEqualTo(8_500);
        assertThat(result.policyDescription()).contains("점주 취소로 전액 환불");
    }

    @ParameterizedTest
    @EnumSource(RefundPolicy.class)
    void noShowHasNoCustomerRefund(RefundPolicy policy) {
        var result = calculator.calculate(10_000, 0, policy, start,
                start.plusDays(1), CancellationActor.CUSTOMER, true);

        assertThat(result.refundRate()).isZero();
        assertThat(result.refundAmount()).isZero();
    }

    @Test
    void changesTheRateAtSeoulMidnightRatherThanAfterTwentyFourHours() {
        var visit = OffsetDateTime.parse("2026-10-28T10:00:00+09:00");
        var beforeMidnight = OffsetDateTime.parse("2026-10-27T14:59:59Z");

        assertThat(calculator.calculate(10_000, 0, RefundPolicy.STRICT, visit,
                beforeMidnight, CancellationActor.CUSTOMER, false).refundRate()).isEqualTo(20);
        assertThat(calculator.calculate(10_000, 0, RefundPolicy.STRICT, visit,
                beforeMidnight.plusSeconds(1), CancellationActor.CUSTOMER, false).refundRate()).isZero();
    }

    @Test
    void usesTheModifiedVisitDateAndIgnoresTimestampOffsetRepresentation() {
        var cancelledAt = start.minusDays(1);
        var original = calculator.calculate(10_000, 0, RefundPolicy.STRICT, start,
                cancelledAt, CancellationActor.CUSTOMER, false);
        var changed = calculator.calculate(10_000, 0, RefundPolicy.STRICT, start.plusDays(2),
                cancelledAt, CancellationActor.CUSTOMER, false);

        assertThat(original.refundRate()).isEqualTo(20);
        assertThat(changed.refundRate()).isEqualTo(100);
        assertThat(calculator.calculate(10_000, 0, RefundPolicy.STRICT,
                start.plusDays(2).withOffsetSameInstant(java.time.ZoneOffset.UTC),
                cancelledAt.withOffsetSameInstant(java.time.ZoneOffset.UTC), CancellationActor.CUSTOMER, false))
                .isEqualTo(changed);
    }

    @Test
    void handlesZeroAndLargeAmountsWithoutOverflow() {
        assertThat(calculator.calculate(0, 0, RefundPolicy.FLEXIBLE, start,
                start.minusDays(1), CancellationActor.CUSTOMER, false).refundAmount()).isZero();
        assertThat(calculator.calculate(Long.MAX_VALUE, 0, RefundPolicy.FLEXIBLE, start,
                start.minusHours(1), CancellationActor.CUSTOMER, false).refundAmount())
                .isEqualTo(Long.MAX_VALUE / 2);
        assertThat(calculator.calculate(Long.MAX_VALUE, 0, RefundPolicy.STRICT, start,
                start.plusDays(1), CancellationActor.OWNER, false).refundAmount()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "100, -1", "100, 101"})
    void rejectsInvalidPaymentAndCumulativeRefundAmounts(long paidAmount, long refundedAmount) {
        assertThatThrownBy(() -> calculator.calculate(paidAmount, refundedAmount, RefundPolicy.FLEXIBLE, start,
                start.minusDays(1), CancellationActor.CUSTOMER, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAPastVisitDateForACustomerCancellationWithoutANoShow() {
        assertThatThrownBy(() -> calculator.calculate(10_000, 0, RefundPolicy.FLEXIBLE, start,
                start.plusDays(1), CancellationActor.CUSTOMER, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotApplyACustomerPolicyToSystemPaymentReconciliation() {
        assertThatThrownBy(() -> calculator.calculate(10_000, 0, RefundPolicy.STRICT, start,
                start.minusDays(1), CancellationActor.SYSTEM, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
