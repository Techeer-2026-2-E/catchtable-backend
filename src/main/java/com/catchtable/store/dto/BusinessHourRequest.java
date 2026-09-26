package com.catchtable.store.dto;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;

// dayOfWeek는 "MONDAY" ~ "SUNDAY", 시각은 "HH:mm"
public record BusinessHourRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull LocalTime openTime,
        @NotNull LocalTime closingTime,
        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
}
