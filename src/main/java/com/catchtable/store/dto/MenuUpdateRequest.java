package com.catchtable.store.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record MenuUpdateRequest(
        @Size(max = 100)
        @Pattern(regexp = ".*\\S.*")
        String name,

        @PositiveOrZero Integer price,

        @Size(max = 1000) String description
) {
}
