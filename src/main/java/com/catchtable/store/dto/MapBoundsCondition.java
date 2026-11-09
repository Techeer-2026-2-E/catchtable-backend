package com.catchtable.store.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// 지도를 이동·확대·축소했을 때 보이는 화면 사각형(남서·북동 모서리). 마커 조회에 쓴다
public record MapBoundsCondition(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double swLat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double swLng,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double neLat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double neLng
) {

    @AssertTrue(message = "남서쪽 좌표는 북동쪽 좌표보다 작아야 합니다")
    public boolean isSouthWestBelowNorthEast() {
        return isOrdered(swLat, swLng, neLat, neLng);
    }

    // 값이 없으면 @NotNull이 먼저 걸러내므로 여기서는 통과시킨다
    static boolean isOrdered(Double swLat, Double swLng, Double neLat, Double neLng) {
        if (swLat == null || swLng == null || neLat == null || neLng == null) {
            return true;
        }
        return swLat < neLat && swLng < neLng;
    }
}
