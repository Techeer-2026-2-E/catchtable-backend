package com.catchtable.store.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// 일주일치 영업시간을 통째로 교체한다. 목록에 없는 요일은 정기 휴무가 된다 (빈 목록 = 전체 휴무)
public record BusinessHourUpdateRequest(
        @NotNull List<@NotNull @Valid BusinessHourRequest> businessHours
) {
}
