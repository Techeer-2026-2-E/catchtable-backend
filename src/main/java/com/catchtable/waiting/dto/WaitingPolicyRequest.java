package com.catchtable.waiting.dto;

import jakarta.validation.constraints.NotNull;

public record WaitingPolicyRequest(
        @NotNull Integer callLimitMinutes
) {
}
