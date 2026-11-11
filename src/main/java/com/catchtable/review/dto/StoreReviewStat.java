package com.catchtable.review.dto;

// 매장 하나의 평균 별점과 리뷰 수. 지도 목록처럼 여러 매장을 한 번에 집계할 때 쓴다
public record StoreReviewStat(Long storeId, Double averageRating, Long reviewCount) {
}
