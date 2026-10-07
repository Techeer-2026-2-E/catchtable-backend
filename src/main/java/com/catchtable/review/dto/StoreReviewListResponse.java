package com.catchtable.review.dto;

import org.springframework.data.domain.Slice;

import java.util.List;

public record StoreReviewListResponse(
        Double averageRating,
        long reviewCount,
        List<StoreReviewResponse> reviews,
        int page,
        int size,
        boolean hasNext
) {
    public static StoreReviewListResponse of(Double averageRating, long reviewCount,
    Slice<StoreReviewResponse> slice){
        return new StoreReviewListResponse(
                averageRating,
                reviewCount,
                slice.getContent(),
                slice.getNumber(),
                slice.getSize(),
                slice.hasNext());
    }
}
