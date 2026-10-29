package com.catchtable.payment.repository;

import com.catchtable.payment.entity.Payment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 결제 행만 잠금
    @Query(value = "select * from payment where id = :paymentId for update", nativeQuery = true)
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    Slice<Payment> findSliceByMemberId(Long memberId, Pageable pageable);

    Optional<Payment> findByIdAndMemberId(Long id, Long memberId);

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
}
