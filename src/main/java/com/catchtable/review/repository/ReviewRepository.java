package com.catchtable.review.repository;

import com.catchtable.review.dto.ReviewSummary;
import com.catchtable.review.entity.Review;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

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

    // 본인 리뷰만, 삭제된 리뷰도 포함 (수정·삭제에서 삭제 여부를 따로 판단)
    Optional<Review> findByIdAndReservation_Member_Id(Long id, Long memberId);

    @Query("""
            select r from Review r
            join fetch r.reservation res
            join fetch res.store
            where res.member.id = :memberId and r.deletedAt is null
            """)
    Slice<Review> findSliceByMemberId(@Param("memberId") Long memberId, Pageable pageable);
}
