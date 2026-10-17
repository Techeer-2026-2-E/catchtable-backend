package com.catchtable.reservation.repository;

import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 상태 변경용 ForUpdate 조회는 같은 예약의 취소·입장·노쇼 요청을 순서대로 처리하기 위해 행을 잠근다.
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 정리 전 선점도 가져온 뒤 서비스에서 조회 시각 기준 상태로 최종 필터링한다.
    @EntityGraph(attributePaths = {"member", "store"})
    @Query("""
            select r from Reservation r
            where r.store.id = :storeId
              and r.store.owner.id = :ownerId
              and r.reservationStartAt >= :start
              and r.reservationStartAt < :end
              and (:status is null or r.status = :status
                or (:status = com.catchtable.reservation.entity.ReservationStatus.EXPIRED
                    and r.status = com.catchtable.reservation.entity.ReservationStatus.PENDING_PAYMENT))
            order by r.reservationStartAt, r.id
            """)
    List<Reservation> findAllForOwner(
            @Param("ownerId") Long ownerId,
            @Param("storeId") Long storeId,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end,
            @Param("status") ReservationStatus status
    );

    @EntityGraph(attributePaths = {"member", "store"})
    Optional<Reservation> findByIdAndStore_Owner_Id(Long id, Long ownerId);

    @EntityGraph(attributePaths = {"store"})
    Optional<Reservation> findByIdAndMember_Id(Long id, Long memberId);

    // 고객 내역 조회용, 매장명을 함께 내려주므로 매장을 같이 가져온다.
    @EntityGraph(attributePaths = {"store"})
    List<Reservation> findAllByMember_IdAndStatusIn(Long memberId, Collection<ReservationStatus> statuses);

    @Query("select r.store.id from Reservation r where r.id = :id and r.store.owner.id = :ownerId")
    Optional<Long> findStoreIdByIdAndOwnerId(@Param("id") Long id, @Param("ownerId") Long ownerId);

    // 매장 공유 락을 배타 락으로 승격하지 않도록 예약 행만 잠근다.
    @Query(value = """
            select r.* from reservation r join store s on s.id = r.store_id
            where r.id = :id and s.owner_id = :ownerId
            for update of r
            """, nativeQuery = true)
    Optional<Reservation> findByIdAndOwnerIdForUpdate(
            @Param("id") Long id,
            @Param("ownerId") Long ownerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id and r.member.id= :memberId")
    Optional<Reservation> findByIdAndMemberIdForUpdate(
            @Param("id") Long id,
            @Param("memberId") Long memberId
    );

    @Query("""
            select r from Reservation r
            where r.store.id = :storeId and r.reservationEndAt > :now
              and (r.status = com.catchtable.reservation.entity.ReservationStatus.CONFIRMED
                or (r.status = com.catchtable.reservation.entity.ReservationStatus.PENDING_PAYMENT
                    and r.holdExpiresAt > :now))
            """)
    List<Reservation> findActiveForStore(@Param("storeId") Long storeId, @Param("now") OffsetDateTime now);

    @Query("""
            select count(r) > 0 from Reservation r
            where r.tableId = :tableId
              and (r.status = com.catchtable.reservation.entity.ReservationStatus.CONFIRMED
                or (r.status = com.catchtable.reservation.entity.ReservationStatus.PENDING_PAYMENT
                    and r.holdExpiresAt > :now))
              and r.reservationEndAt > :now
              and (:inactive = true or r.partySize < :minCapacity or r.partySize > :capacity)
            """)
    boolean existsConflictingTableReservation(
            @Param("tableId") Long tableId,
            @Param("now") OffsetDateTime now,
            @Param("inactive") boolean inactive,
            @Param("minCapacity") int minCapacity,
            @Param("capacity") int capacity
    );

    // 생성 시에는 해당 매장만 정리한다. 다른 상태 변경이 잠근 예약은 건너뛰어 대기·교착을 피한다.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            WITH expiring AS (
                SELECT id FROM reservation
                WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= :now
                  AND (CAST(:storeId AS BIGINT) IS NULL OR store_id = :storeId)
                ORDER BY id
                FOR UPDATE SKIP LOCKED
            )
            UPDATE reservation r SET status = 'EXPIRED', updated_at = :now
            FROM expiring WHERE r.id = expiring.id
            """, nativeQuery = true)
    int expireHolds(@Param("storeId") Long storeId, @Param("now") OffsetDateTime now);

    // 방문 확인을 마치고 이용시간이 끝난 확정 예약만 일괄 완료 처리한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
            set r.status = com.catchtable.reservation.entity.ReservationStatus.COMPLETED,
                r.completedAt = :completedAt,
                r.updatedAt = :completedAt
            where r.status = com.catchtable.reservation.entity.ReservationStatus.CONFIRMED
              and r.checkedInAt is not null
              and r.reservationEndAt <= :completedAt
            """)
    int completeVisitedReservations(@Param("completedAt") OffsetDateTime completedAt);


}
