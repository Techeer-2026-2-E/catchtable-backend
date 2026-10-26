package com.catchtable.review.repository;

import com.catchtable.review.dto.ReviewSummary;
import com.catchtable.review.entity.Review;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 수정·삭제를 직렬화해 삭제 표시가 이전 수정에 덮이지 않게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Review> findByIdAndReservation_Member_Id(Long id, Long memberId);

    @Query("""
            select r from Review r
            join fetch r.reservation res
            join fetch res.store
            where res.member.id = :memberId and r.deletedAt is null
            """)
    Slice<Review> findSliceByMemberId(@Param("memberId") Long memberId, Pageable pageable);
}
