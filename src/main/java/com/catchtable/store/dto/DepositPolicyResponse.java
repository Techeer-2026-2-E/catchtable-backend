package com.catchtable.store.dto;

import com.catchtable.reservation.entity.RefundPolicy;
import com.catchtable.store.entity.Store;

public record DepositPolicyResponse(
        boolean depositEnabled,
        long depositAmount,
        RefundPolicy refundPolicy
) {
    public static DepositPolicyResponse from(Store store)
    {
        return new DepositPolicyResponse(
                store.isDepositEnabled(),
                store.getDepositAmount(),
                store.getRefundPolicy()
        );
    }
}
