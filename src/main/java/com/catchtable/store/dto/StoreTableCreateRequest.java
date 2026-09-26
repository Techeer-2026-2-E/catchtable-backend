package com.catchtable.store.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StoreTableCreateRequest
        (
                @NotNull @Positive Integer tableNumber,
                @NotNull @Positive Integer capacity
                ){

}
