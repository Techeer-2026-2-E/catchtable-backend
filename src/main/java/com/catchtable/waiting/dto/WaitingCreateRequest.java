package com.catchtable.waiting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 웨이팅 신청 요청
public record WaitingCreateRequest(
        // 인원수: 필수, 1~20명
        @NotNull(message = "인원수는 필수입니다.")
        @Min(value = 1, message = "인원수는 1명 이상이어야 합니다.")
        @Max(value = 20, message = "인원수는 20명 이하여야 합니다.")
        Integer partyCount
) {
}