package com.catchtable.waiting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OwnerWaitingCancelRequest(@NotBlank @Size(max = 300) String reason) {
}
