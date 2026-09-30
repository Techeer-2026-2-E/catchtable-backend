package com.catchtable.reservation.repository;

import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

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
}
