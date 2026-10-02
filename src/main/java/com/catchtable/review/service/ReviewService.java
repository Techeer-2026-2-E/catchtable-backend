package com.catchtable.review.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.review.dto.ReviewCreateRequest;
import com.catchtable.review.dto.ReviewResponse;
import com.catchtable.review.entity.Review;
import com.catchtable.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
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
}
