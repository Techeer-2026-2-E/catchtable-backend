package com.catchtable.waiting.dto;

import jakarta.validation.constraints.NotNull;

public record WaitingEnterRequest(@NotNull Long tableId) {
}
