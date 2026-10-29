package com.catchtable.store.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 메인 화면의 주변 매장 조회. 현재 위치는 프런트가 GPS 좌표로 보낸다
public record NearbyStoreCondition(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size
) {
    public NearbyStoreCondition {
        if (page == null) page = 0;
        if (size == null) size = 10;
    }
}
