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
        String description,
        String phone,
        String imageUrl,
        boolean waitingAvailable,
        boolean reservationEnabled,
        List<BusinessHourResponse> businessHours,   // 없는 요일은 휴무
        List<MenuResponse> menus
){

    public static StoreDetailResponse of(Store store, List<BusinessHourResponse> businessHours, List<MenuResponse> menus)
    {
        return new StoreDetailResponse(
                store.getId(),
                store.getName(),
                store.getCategory(),
                store.getCategory().getDescription(),
                store.getAddress(),
                store.getDescription(),
                store.getPhone(),
                store.getImageUrl(),
                store.isWaitingAvailable(),
                store.getOwner() != null,
                businessHours,
                menus
        );

    }

}
