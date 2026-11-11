package com.catchtable.store.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 화면 사각형 안의 매장 목록 조회. 내 현재 위치(lat, lng)에서 가까운 순으로 정렬하므로 필수다
public record MapBoundsListCondition(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double swLat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double swLng,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double neLat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double neLng,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size
) {
    public MapBoundsListCondition {
        if (page == null) page = 0;
        if (size == null) size = 10;
    }

    @AssertTrue(message = "남서쪽 좌표는 북동쪽 좌표보다 작아야 합니다")
    public boolean isSouthWestBelowNorthEast() {
        return MapBoundsCondition.isOrdered(swLat, swLng, neLat, neLng);
    }
}
