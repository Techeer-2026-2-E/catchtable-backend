package com.catchtable.store.dto;

import com.catchtable.store.entity.SpecialBusinessDay;

import java.time.LocalDate;
import java.time.LocalTime;

public record SpecialBusinessDayResponse(
        LocalDate businessDate,
        boolean closed,
        LocalTime openTime,
        LocalTime closingTime,
        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
    public static SpecialBusinessDayResponse from(SpecialBusinessDay day) {
        return new SpecialBusinessDayResponse(
                day.getBusinessDate(),
                day.isClosed(),
                day.getOpenTime(),
                day.getClosingTime(),
                day.getBreakStartTime(),
                day.getBreakEndTime()
        );
    }
}
