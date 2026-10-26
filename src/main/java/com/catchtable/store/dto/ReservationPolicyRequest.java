package com.catchtable.store.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ReservationPolicyRequest(
        @NotNull @Positive Integer reservationDurationMinutes,   // 이용시간
        @NotNull @Positive Integer reservationSlotMinutes,       // 슬롯 간격
        @NotNull @PositiveOrZero Integer arrivalGraceMinutes,    // 도착 유예
        @NotNull @Positive Integer bookingOpenDays,              // 오늘부터 며칠 뒤 날짜까지
        @NotNull @PositiveOrZero Integer bookingDeadlineMinutes  // 시작 몇 분 전 마감
) {
}
