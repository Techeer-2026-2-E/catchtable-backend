package com.catchtable.store.dto;

import com.catchtable.store.entity.StoreCategory;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StoreInfoUpdateRequest(

        @Size(max=200)
        @Pattern(regexp=".*\\S.*")
        String name,

        StoreCategory category,

        @Size(max=255)
        @Pattern(regexp=".*\\S.*")
        String address,
        @Size(max = 1000)
        String description,

        @Pattern(regexp = "^$|^0\\d{1,2}-\\d{3,4}-\\d{4}$")
        String phone,

        @Size(max = 2048)
        @Pattern(regexp = "^$|^https?://.+")
        String imageUrl
) {
}
