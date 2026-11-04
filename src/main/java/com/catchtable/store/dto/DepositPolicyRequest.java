package com.catchtable.store.dto;

import com.catchtable.reservation.entity.RefundPolicy;
import jakarta.validation.constraints.NotNull;

public record DepositPolicyRequest(
        @NotNull Boolean depositEnabled,   // 예약금 사용 여부
        Long depositAmount,                // 건당 금액. 끄면 무시
        RefundPolicy refundPolicy          // 끄면 무시
) {
}
