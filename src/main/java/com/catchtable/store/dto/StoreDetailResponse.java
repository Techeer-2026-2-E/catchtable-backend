package com.catchtable.store.dto;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;

import java.util.List;

public record StoreDetailResponse (
        Long id,
        String name,
        StoreCategory category,
        String categoryName,
        String address,
        boolean waitingAvailable,
        List<BusinessHourResponse> businessHours   // 없는 요일은 휴무
){

    public static StoreDetailResponse of(Store store, List<BusinessHourResponse> businessHours)
    {
        return new StoreDetailResponse(
                store.getId(),
                store.getName(),
                store.getCategory(),
                store.getCategory().getDescription(),
                store.getAddress(),
                store.isWaitingAvailable(),
                businessHours
        );

    }

}
