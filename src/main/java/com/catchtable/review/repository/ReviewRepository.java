package com.catchtable.review.repository;

import com.catchtable.review.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationId(Long reservationId);
}
