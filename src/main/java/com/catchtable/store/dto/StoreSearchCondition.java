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
        String keyword,                                                  // 매장 이름 일부
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,   // 2026-10-03)
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,   // 19:00
        @Positive Integer partySize,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size
) {
    public StoreSearchCondition {
        if (page == null) page = 0;
        if (size == null) size = 10;
        // 공백만 있는 키워드는 조건이 없는 것으로 본다
        keyword = (keyword == null || keyword.isBlank()) ? null : keyword.strip();
    }

    // LIKE 패턴. 입력한 \ % _ 는 문자 그대로 찾도록 이스케이프한다
    public String keywordPattern()
    {
        if (keyword == null) return null;
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public boolean hasDateTime()
    {
        return date !=null && time !=null;
    }

    // 키워드·인원·날짜 필터가 하나라도 있으면 단순 페이징으로는 거를 수 없다
    public boolean hasFilter()
    {
        return keyword != null || partySize != null || hasDateTime();
    }

    @AssertTrue(message="날짜와 시간은 함께 입력해야 합니다")
    public boolean isDateTimePaired()
    {
        return (date==null)==(time==null);
    }
}
