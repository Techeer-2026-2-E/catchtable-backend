package com.catchtable.store.dto;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;

public record StoreSummaryResponse(
        Long id,
        String name,
        StoreCategory category,
        String categoryName,
        String address,
        boolean waitingAvailable
) {
    public static StoreSummaryResponse of(Store store) {
        return new StoreSummaryResponse(
                store.getId(),
                store.getName(),
                store.getCategory(),
                store.getCategory().getDescription(),
                store.getAddress(),
                store.isWaitingAvailable()
        );
    }
}
