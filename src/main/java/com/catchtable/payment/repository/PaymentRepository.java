package com.catchtable.payment.repository;

import com.catchtable.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 결제 행만 잠금
    @Query(value = "select * from payment where id = :paymentId for update", nativeQuery = true)
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    List<Payment> findAllByMemberIdOrderByIdDesc(Long memberId);

    Optional<Payment> findByIdAndMemberId(Long id, Long memberId);

    List<Payment> findAllByReservation_Id(Long reservationId);

    @Query(value = "select * from payment where reservation_id = :reservationId order by id for update",
            nativeQuery = true)
    List<Payment> findAllByReservationIdForUpdate(@Param("reservationId") Long reservationId);
}
