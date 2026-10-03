package com.catchtable.store.dto;

import com.catchtable.store.entity.StoreCategory;

public record CategoryResponse(
        StoreCategory code,
        String name
) {
    public static CategoryResponse of(StoreCategory category) {
        return new CategoryResponse(category, category.getDescription());
    }
}