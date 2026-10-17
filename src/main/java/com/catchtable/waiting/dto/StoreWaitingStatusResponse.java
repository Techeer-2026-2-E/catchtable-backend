package com.catchtable.waiting.dto;

import com.catchtable.store.entity.Store;

public record StoreWaitingStatusResponse(
        Long storeId,
        boolean waitingAvailable
) {
    public static StoreWaitingStatusResponse from(Store store) {
        return new StoreWaitingStatusResponse(store.getId(), store.isWaitingAvailable());
    }
}
