package com.catchtable.store.dto;

import com.catchtable.store.entity.StoreCategory;

public record NearbyStoreResponse(
        Long id,
        String name,
        StoreCategory category,
        String categoryName,
        String address,
        boolean waitingAvailable,
        String imageUrl,
        int distanceMeters
) {
}
