package com.catchtable.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record MenuCreateRequest(
        @NotBlank @Size(max=100) String name,
        @NotNull @PositiveOrZero Integer price,
        @Size(max=1000) String description

) {
}
