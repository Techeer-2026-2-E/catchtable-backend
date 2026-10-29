package com.catchtable.payment.repository;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 결제 행만 잠금
    @Query(value = "select * from payment where id = :paymentId for update", nativeQuery = true)
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    Slice<Payment> findSliceByMemberId(Long memberId, Pageable pageable);

    Optional<Payment> findByIdAndMemberId(Long id, Long memberId);

    List<Payment> findAllByReservation_Id(Long reservationId);

    @Query(value = "select * from payment where reservation_id = :reservationId order by id for update",
            nativeQuery = true)
    List<Payment> findAllByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    Optional<Payment> findByOrderIdAndMemberId(String orderId, Long memberId);

    @Query("select p.reservation.id from Payment p where p.orderId = :orderId and p.member.id = :memberId")
    Optional<Long> findReservationIdByOrderIdAndMemberId(@Param("orderId") String orderId,
                                                       @Param("memberId") Long memberId);

    @Query(value = "select * from payment where order_id = :orderId and member_id = :memberId for update",
            nativeQuery = true)
    Optional<Payment> findByOrderIdAndMemberIdForUpdate(@Param("orderId") String orderId,
                                                       @Param("memberId") Long memberId);

    // 다른 결제에 쓰인 paymentKey
    boolean existsByPaymentKeyAndIdNot(String paymentKey, Long id);

    boolean existsByReservationIdAndIdNotAndStatusIn(Long reservationId, Long id, Collection<PaymentStatus> statuses);

    Optional<Payment> findByOrderId(String orderId);

    Optional<Payment> findByPaymentKey(String paymentKey);

    @Query("select p.reservation.id from Payment p where p.id = :paymentId")
    Optional<Long> findReservationIdById(@Param("paymentId") Long paymentId);

    // 재확인 대상: 결과 불명, 승인 진행 중 예약, 격리 예약, 진행 중 환불
    // ponytail: id 순 고정 상한, 계속 불명인 건이 많아지면 마지막 확인 시각 순으로
    @Query(value = """
            select p.id from payment p
            join reservation r on r.id = p.reservation_id
            where p.status = 'TIMEOUT'
               or (r.status = 'PENDING_PAYMENT' and r.approval_payment_id = p.id)
               or (r.status = 'PENDING_PAYMENT' and r.approval_quarantined
                   and p.status in ('DONE', 'PARTIAL_REFUNDED', 'REFUNDED'))
               or exists (select 1 from payment_refund f
                          where f.payment_id = p.id and f.status in ('REQUESTED', 'UNKNOWN'))
            order by p.id
            limit :limit
            """, nativeQuery = true)
    List<Long> findReconcileTargets(@Param("limit") int limit);
}
