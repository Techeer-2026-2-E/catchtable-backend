package com.catchtable.store.dto;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;

public record StoreInfoResponse(
        Long id,
        String name,
        StoreCategory category,
        String categoryName,
        String address,
        String description,
        String phone,
        String imageUrl
) {

    public static StoreInfoResponse from(Store store)
    {
        return new StoreInfoResponse(
                store.getId(),
                store.getName(),
                store.getCategory(),
                store.getCategory().getDescription(),
                store.getAddress(),
                store.getDescription(),
                store.getPhone(),
                store.getImageUrl()
        );
    }
}
