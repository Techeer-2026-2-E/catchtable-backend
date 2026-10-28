package com.catchtable.payment.repository;

import com.catchtable.payment.entity.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, Long> {
    List<PaymentStatusHistory> findAllByPaymentIdOrderByIdAsc(Long paymentId);
}
