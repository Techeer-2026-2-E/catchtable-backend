package com.catchtable.review.dto;

import jakarta.validation.constraints.*;

public record ReviewCreateRequest(
        @NotNull
        Long reservationId,

        @NotNull
        @Min(1)
        @Max(5)
        Integer rating,

        @NotBlank
        @Size(max=1000)
        String content,

        @Size(max=2048)
        @Pattern(regexp = "^$|^https?://.+")
        String imageUrl

) {


}
