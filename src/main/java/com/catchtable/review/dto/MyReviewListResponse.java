package com.catchtable.review.dto;

import org.springframework.data.domain.Slice;

import java.util.List;

public record MyReviewListResponse(
        List<MyReviewResponse> reviews,
        int page,
        int size,
        boolean hasNext
) {
    public static MyReviewListResponse of(Slice<MyReviewResponse> slice) {
        return new MyReviewListResponse(
                slice.getContent(),
                slice.getNumber(),
                slice.getSize(),
                slice.hasNext());
    }
}
