package com.catchtable.store.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

// 날짜는 URL로 받는다. closed가 false이면 openTime·closingTime이 필수이고, 시각은 "HH:mm"
public record SpecialBusinessDayRequest(
        @NotNull Boolean closed,
        LocalTime openTime,
        LocalTime closingTime,
        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
    // 휴무 요청에 시각이 함께 오면 프런트 실수일 수 있으므로 무시하지 않고 거절한다.
    @AssertTrue(message = "휴무일에는 시각을 입력할 수 없습니다.")
    public boolean isTimesEmptyWhenClosed() {
        return !Boolean.TRUE.equals(closed)
                || (openTime == null && closingTime == null && breakStartTime == null && breakEndTime == null);
    }
}
