package com.catchtable.review.dto;

import com.catchtable.review.entity.Review;

import java.time.OffsetDateTime;

public record StoreReviewResponse(
        Long reviewId,
        String writerName,
        int rating,
        String content,
        String imageUrl,
        OffsetDateTime createdAt
) {

    public static StoreReviewResponse from(Review review) {
        return new StoreReviewResponse(
                review.getId(),
                maskName(review.getReservation().getMember().getName()),
                review.getRating(),
                review.getContent(),
                review.getImageUrl(),
                review.getCreatedAt());
    }
    // 방준혁 → 방*혁, 남궁민수 → 남**수, 이서 → 이*
    private static String maskName(String name) {
        if (name.length() <= 1) return "*";
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
