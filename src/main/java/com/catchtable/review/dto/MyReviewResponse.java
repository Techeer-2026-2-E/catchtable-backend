package com.catchtable.review.dto;

import com.catchtable.reservation.entity.Reservation;
import com.catchtable.review.entity.Review;

import java.time.OffsetDateTime;

public record MyReviewResponse(
        Long reviewId,
        Long storeId,
        String storeName,
        OffsetDateTime visitedAt,
        int rating,
        String content,
        String imageUrl,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static MyReviewResponse from(Review review) {
        Reservation reservation = review.getReservation();
        return new MyReviewResponse(
                review.getId(),
                reservation.getStore().getId(),
                reservation.getStore().getName(),
                reservation.getReservationStartAt(),
                review.getRating(),
                review.getContent(),
                review.getImageUrl(),
                review.getCreatedAt(),
                review.getUpdatedAt());
    }
}
