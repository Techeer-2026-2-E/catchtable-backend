package com.catchtable.waiting.dto;

import com.catchtable.store.entity.Store;

public record WaitingPolicyResponse(
        Long storeId,
        int callLimitMinutes
) {
    public static WaitingPolicyResponse from(Store store) {
        return new WaitingPolicyResponse(store.getId(), store.getWaitingCallLimitMinutes());
    }
}
