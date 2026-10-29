package com.catchtable.store.dto;

import com.catchtable.store.entity.StoreCategory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;

public record StoreSearchCondition(
        StoreCategory category,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,   // 2026-10-03)
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,   // 19:00
        @Positive Integer partySize,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size
) {
    public StoreSearchCondition {
        if (page == null) page = 0;
        if (size == null) size = 10;
    }

    public boolean hasDateTime()
    {
        return date !=null && time !=null;
    }

    // 인원·날짜 필터가 하나라도 있으면 DB 페이징만으로는 거를 수 없다
    public boolean hasFilter()
    {
        return partySize != null || hasDateTime();
    }

    @AssertTrue(message="날짜와 시간은 함께 입력해야 합니다")
    public boolean isDateTimePaired()
    {
        return (date==null)==(time==null);
    }
}
