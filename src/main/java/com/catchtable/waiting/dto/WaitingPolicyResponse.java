package com.catchtable.waiting.dto;

import com.catchtable.store.entity.Store;

public record WaitingPolicyResponse(
        Long storeId,
        boolean open,
        int arrivalLimitMinutes
) {
    public static WaitingPolicyResponse from(Store store) {
        return new WaitingPolicyResponse(store.getId(), store.isWaitingAvailable(), store.getWaitingCallLimitMinutes());
    }
}
