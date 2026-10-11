package com.catchtable.review.dto;

import com.catchtable.review.entity.Review;

import java.time.OffsetDateTime;

public record ReviewResponse(
        Long reviewId,
        Long reservationId,
        int rating,
        String content,
        String imageUrl,
        OffsetDateTime createdAt
) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getReservation().getId(),
                review.getRating(),
                review.getContent(),
                review.getImageUrl(),
                review.getCreatedAt());
    }
}
