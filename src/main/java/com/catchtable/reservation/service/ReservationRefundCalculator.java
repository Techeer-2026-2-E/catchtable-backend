package com.catchtable.reservation.service;

import com.catchtable.reservation.dto.RefundCalculation;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.RefundPolicy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
public class ReservationRefundCalculator {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    /** 금액·정책은 예약 당시 스냅샷, 예약 시각은 변경 후 현재 값을 받는다. */
    public RefundCalculation calculate(long paidAmount, long refundedAmount, RefundPolicy policy,
                                       OffsetDateTime reservationStartAt, OffsetDateTime cancelledAt,
                                       CancellationActor actor, boolean noShow) {
        if (paidAmount < 0 || refundedAmount < 0 || refundedAmount > paidAmount) {
            throw new IllegalArgumentException("결제액과 누적 환불액이 올바르지 않습니다.");
        }
        Objects.requireNonNull(policy);
        Objects.requireNonNull(actor);
        // shortcut: 시스템 취소·결제 보정의 환불은 공통 결제 계약 확정 후 연결한다.
        if (actor == CancellationActor.SYSTEM) {
            throw new IllegalArgumentException("고객 또는 점주 취소의 환불만 계산할 수 있습니다.");
        }
        var reservationDate = Objects.requireNonNull(reservationStartAt)
                .atZoneSameInstant(STORE_ZONE).toLocalDate();
        var cancellationDate = Objects.requireNonNull(cancelledAt)
                .atZoneSameInstant(STORE_ZONE).toLocalDate();
        long daysBefore = ChronoUnit.DAYS.between(cancellationDate, reservationDate);
        int rate = refundRate(policy, actor, noShow, daysBefore);
        long totalRefundable = BigDecimal.valueOf(paidAmount).multiply(BigDecimal.valueOf(rate))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
        long refundAmount = Math.max(0, totalRefundable - refundedAmount);
        String reason = actor == CancellationActor.OWNER ? "점주 취소로 전액 환불"
                : noShow ? "노쇼로 환불 불가" : policy.getDescription() + " 환불 정책";
        String description = "%s, 예약일 %s, 취소일 %s, 환불 비율 %d%%, 기환불액 %d원 차감"
                .formatted(reason, reservationDate, cancellationDate, rate, refundedAmount);
        return new RefundCalculation(rate, refundAmount, description);
    }

    private static int refundRate(RefundPolicy policy, CancellationActor actor, boolean noShow, long daysBefore) {
        if (actor == CancellationActor.OWNER) return 100;
        if (noShow) return 0;
        if (daysBefore < 0) {
            throw new IllegalArgumentException("예약일이 지난 고객 취소는 처리할 수 없습니다.");
        }
        return switch (policy) {
            case FLEXIBLE -> daysBefore >= 1 ? 100 : 50;
            case STANDARD -> {
                if (daysBefore >= 2) yield 100;
                yield daysBefore == 1 ? 50 : 0;
            }
            case STRICT -> {
                if (daysBefore >= 3) yield 100;
                if (daysBefore == 2) yield 50;
                yield daysBefore == 1 ? 20 : 0;
            }
        };
    }
}
