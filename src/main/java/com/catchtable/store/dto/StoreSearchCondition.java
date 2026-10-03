package com.catchtable.store.dto;

import com.catchtable.store.entity.StoreCategory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;

public record StoreSearchCondition(
        StoreCategory category,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,   // 2026-10-03)
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,   // 19:00
        @Positive Integer partySize
) {

    public boolean hasDateTime()
    {
        return date !=null && time !=null;
    }
    @AssertTrue(message="날짜와 시간은 함께 입력해야 합니다")
    public boolean isDateTimePaired()
    {
        return (date==null)==(time==null);
    }
}
