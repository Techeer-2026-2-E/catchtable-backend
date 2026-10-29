package com.catchtable.reservation.repository;

import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.reservation.entity.ReservationRefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface ReservationRefundRequestRepository extends JpaRepository<ReservationRefundRequest, Long> {
    Optional<ReservationRefundRequest> findByReservationIdAndIdempotencyKey(Long reservationId, String key);
    Optional<ReservationRefundRequest> findFirstByReservationIdAndStatusInOrderByIdAsc(
            Long reservationId, Collection<PaymentRefundStatus> statuses);
    boolean existsByReservationIdAndStatusIn(Long reservationId, Collection<PaymentRefundStatus> statuses);

    @Query("select count(f) > 0 from PaymentRefund f where f.payment.reservation.id = :id and f.status in :statuses")
    boolean hasCommonInFlight(@Param("id") Long reservationId,
                             @Param("statuses") Collection<PaymentRefundStatus> statuses);
}
