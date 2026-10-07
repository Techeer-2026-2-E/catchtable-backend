package com.catchtable.review.repository;

import com.catchtable.review.dto.ReviewSummary;
import com.catchtable.review.entity.Review;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationId(Long reservationId);

    @Query("""
            select r from Review r
            join fetch r.reservation res
            join fetch res.member
            where res.store.id = :storeId and r.deletedAt is null
            """)
    Slice<Review> findSliceByStoreId(@Param("storeId") Long storeId, Pageable pageable);

    @Query("""
        select new com.catchtable.review.dto.ReviewSummary(avg(r.rating), count(r))
        from Review r
        where r.reservation.store.id= :storeId
        and r.deletedAt is null
""")
    ReviewSummary summarizeByStoreId(@Param("storeId") Long storeId);
}
