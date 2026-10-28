package com.catchtable.payment.repository;

import com.catchtable.payment.entity.PaymentRefund;
import com.catchtable.payment.entity.PaymentRefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    Optional<PaymentRefund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    boolean existsByPaymentIdAndStatusIn(Long paymentId, Collection<PaymentRefundStatus> statuses);

    List<PaymentRefund> findAllByPaymentIdOrderByIdAsc(Long paymentId);
}
