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
        Review saved=reviewRepository.saveAndFlush(review);   // 중복이면 여기서 unique 예외 → store는 그대로

        // store 행 잠금은 이 UPDATE부터 커밋까지다. 잠금 시간을 줄이려고 마지막에 둔다
        storeRepository.applyReviewChange(reservation.getStore().getId(), 1, saved.getRating());
        return ReviewResponse.from(saved);
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

        int previousRating = review.getRating();
        review.update(request.rating(), request.content(), request.imageUrl());

        int ratingDelta = review.getRating() - previousRating;   // rating을 안 보냈으면 0
        if (ratingDelta != 0) {
            storeRepository.applyReviewChange(storeIdOf(review), 0, ratingDelta);
        }
        return ReviewResponse.from(review);
    }

    @Transactional
    public void deleteReview(Long memberId, Long reviewId)
    {
        Review review = reviewRepository.findByIdAndReservation_Member_Id(reviewId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        // 이미 삭제됐으면 아무것도 안 함 → 204. 이때 store에서 두 번 빼지 않는다
        if (review.delete(OffsetDateTime.now(clock))) {
            storeRepository.applyReviewChange(storeIdOf(review), -1, -review.getRating());
        }
    }

    // review에는 store_id가 없어 예약을 거친다. store는 프록시라 id만 읽으면 추가 조회가 없다
    private static Long storeIdOf(Review review)
    {
        return review.getReservation().getStore().getId();
    }

    private static Double roundRating(Double average)
    {
        if (average == null) {
            return null;
        }
        return Math.round(average * 10) / 10.0;
    }

}
