package com.catchtable.store.dto;

import com.catchtable.store.entity.TableStatus;
import jakarta.validation.constraints.Positive;

public record StoreTableUpdateRequest(
        @Positive Integer tableNumber,
        @Positive Integer capacity,
        TableStatus status
) {
}
