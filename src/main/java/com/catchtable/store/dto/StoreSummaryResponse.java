package com.catchtable.store.dto;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;

import java.time.LocalDateTime;

// 매장 목록(검색) 한 건. 지도 목록(MapStoreResponse)과 같은 이름·규칙으로 영업 상태·별점·위치를 함께 준다
public record StoreSummaryResponse(
        Long id,
        String name,
        StoreCategory category,
        String categoryName,
        String address,
        boolean waitingAvailable,
        String imageUrl,
        String area,                  // 주소의 구 이름. 형식이 다르면 null
        Double latitude,              // WGS84. 좌표가 없는 매장은 null
        Double longitude,
        boolean reservationEnabled,   // 점주가 있는 입점 매장만 true
        OpenStatus openStatus,        // 영업시간 정보가 없는 매장은 null
        LocalDateTime nextChangeAt,   // 다음에 상태가 바뀌는 한국 시각. 없으면 null
        Double ratingAverage,         // 소수 첫째 자리. 리뷰가 없으면 null
        long reviewCount
) {
    public static StoreSummaryResponse of(Store store) {
        return new StoreSummaryResponse(
                store.getId(),
                store.getName(),
                store.getCategory(),
                store.getCategory().getDescription(),
                store.getAddress(),
                store.isWaitingAvailable(),
                store.getImageUrl(),
                MapStoreResponse.areaOf(store.getAddress()),
                store.getLatitude(),
                store.getLongitude(),
                store.getOwner() != null,
                null,
                null,
                null,
                0
        );
    }

    // 한 페이지를 모아 읽은 영업 상태와 별점을 합친 새 응답
    public StoreSummaryResponse withOpenStatusAndReview(OpenStatusInfo openStatusInfo, Double ratingAverage,
                                                        long reviewCount) {
        return new StoreSummaryResponse(id, name, category, categoryName, address, waitingAvailable, imageUrl,
                area, latitude, longitude, reservationEnabled,
                openStatusInfo == null ? null : openStatusInfo.status(),
                openStatusInfo == null ? null : openStatusInfo.nextChangeAt(),
                ratingAverage, reviewCount);
    }
}
