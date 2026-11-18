package com.catchtable.store.dto;

// 지도 위의 핀 하나. 별점 높은 순으로 내려가며, rank 1~3은 프런트가 매장명을 함께 표시한다
public record MapMarkerResponse(
        Long id,
        String name,
        Double latitude,
        Double longitude,
        Double ratingAverage,   // 소수 첫째 자리. 리뷰가 없으면 null
        int reviewCount,
        int rank                // 화면 안 순위, 1부터
) {
}
