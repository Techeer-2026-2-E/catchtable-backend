package com.catchtable.store.dto;

import com.catchtable.store.entity.BusinessHour;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record BusinessHourResponse(
        DayOfWeek dayOfWeek,
        LocalTime openTime,
        LocalTime closingTime,
        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
    public static BusinessHourResponse from(BusinessHour businessHour) {
        return new BusinessHourResponse(
                businessHour.getDayOfWeek(),
                businessHour.getOpenTime(),
                businessHour.getClosingTime(),
                businessHour.getBreakStartTime(),
                businessHour.getBreakEndTime()
        );
    }
}
