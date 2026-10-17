package com.catchtable.waiting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WaitingCreateRequest(
        @NotNull(message = "매장 ID는 필수입니다.")
        Long storeId,

        @NotNull(message = "인원수는 필수입니다.")
        @Min(value = 1, message = "인원수는 1명 이상이어야 합니다.")
        @Max(value = 20, message = "인원수는 20명 이하여야 합니다.")
        Integer partyCount
) {
}