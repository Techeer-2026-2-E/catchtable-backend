package com.catchtable.review.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.review.dto.*;
import com.catchtable.review.entity.Review;
import com.catchtable.review.repository.ReviewRepository;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private static final Duration WRITABLE_PERIOD=Duration.ofDays(30);

    private final ReviewRepository reviewRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;
    private final StoreRepository storeRepository;

    @Transactional
    public ReviewResponse createReview(Long memberId, ReviewCreateRequest request)
    {
        Reservation reservation = reservationRepository.findById(request.reservationId())
                .filter(r -> r.getMember().getId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if(reservation.getStatus()!= ReservationStatus.COMPLETED)
        {
            throw new BusinessException(ErrorCode.REVIEW_NOT_WRITABLE);
        }
        OffsetDateTime deadline=reservation.getCompletedAt().plus(WRITABLE_PERIOD);
        if(OffsetDateTime.now(clock).isAfter(deadline))
        {
            throw new BusinessException(ErrorCode.REVIEW_WRITE_PERIOD_EXPIRED);
        }

        if(reviewRepository.existsByReservationId(reservation.getId()))
        {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        Review review=Review.ofReservation(reservation, request.rating(), request.content(), request.imageUrl());

        return ReviewResponse.from(reviewRepository.saveAndFlush(review));
    }

    public StoreReviewListResponse getStoreReviews(Long storeId, ReviewListCondition condition)
    {
        if(!storeRepository.existsById(storeId))
        {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }

        ReviewSummary summary=reviewRepository.summarizeByStoreId(storeId);
        Slice<StoreReviewResponse> reviews=reviewRepository
                .findSliceByStoreId(storeId, condition.toPageable())
                .map(StoreReviewResponse::from);

        return StoreReviewListResponse.of(
                roundRating(summary.averageRating()),
                summary.reviewCount(),
                reviews);

    }
    public MyReviewListResponse getMyReviews(Long memberId, ReviewListCondition condition)
    {
        Slice<MyReviewResponse> reviews = reviewRepository
                .findSliceByMemberId(memberId, condition.toPageable())
                .map(MyReviewResponse::from);
        return MyReviewListResponse.of(reviews);
    }

    @Transactional
    public ReviewResponse updateReview(Long memberId, Long reviewId, ReviewUpdateRequest request)
    {
        Review review = reviewRepository.findByIdAndReservation_Member_Id(reviewId, memberId)
                .filter(r -> !r.isDeleted())   // 삭제된 리뷰는 고칠 대상이 없음 → 404
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        review.update(request.rating(), request.content(), request.imageUrl());
        return ReviewResponse.from(review);
    }

    @Transactional
    public void deleteReview(Long memberId, Long reviewId)
    {
        Review review = reviewRepository.findByIdAndReservation_Member_Id(reviewId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        review.delete(OffsetDateTime.now(clock));   // 이미 삭제됐으면 아무것도 안 함 → 204
    }

    private static Double roundRating(Double average)
    {
        if (average == null) {
            return null;
        }
        return Math.round(average * 10) / 10.0;
    }

}
