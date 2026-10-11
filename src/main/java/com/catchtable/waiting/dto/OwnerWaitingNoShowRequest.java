package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.WaitingEndReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OwnerWaitingNoShowRequest(
        @NotNull WaitingEndReason reason,
        @NotBlank @Size(max = 300) String detail
) {
}
