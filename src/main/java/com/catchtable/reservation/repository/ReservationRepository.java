package com.catchtable.reservation.repository;

import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// 상태 변경용 ForUpdate 조회는 같은 예약의 취소·입장·노쇼 요청을 순서대로 처리하기 위해 행을 잠근다.
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @EntityGraph(attributePaths = {"member", "store"})
    @Query("""
            select r from Reservation r
            where r.store.id = :storeId
              and r.store.owner.id = :ownerId
              and r.reservationStartAt >= :start
              and r.reservationStartAt < :end
              and (:status is null or r.status = :status)
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id and r.store.owner.id = :ownerId")
    Optional<Reservation> findByIdAndOwnerIdForUpdate(
            @Param("id") Long id,
            @Param("ownerId") Long ownerId
    );
}
