package com.catchtable.payment.repository;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;

// 매출 집계 전용 조회. 결제 저장소를 건드리지 않고 결제·예약 원장을 읽기만 한다
public interface PaymentSalesRepository extends Repository<Payment, Long> {

    @Query("select s.id from Store s where s.id = :storeId and s.owner.id = :ownerId")
    Optional<Long> findStoreIdByIdAndOwnerId(@Param("storeId") Long storeId, @Param("ownerId") Long ownerId);

    // [start, end) 안에 승인된 결제의 결제액과 누적 환불액. 같은 행 집합이라 기준이 동일하다
    @Query("""
            select coalesce(sum(p.amount), 0L) as approvedAmount,
                   coalesce(sum(p.refundedAmount), 0L) as refundedAmount
            from Payment p
            where p.reservation.store.id = :storeId
              and p.status in :statuses
              and p.approvedAt >= :start
              and p.approvedAt < :end
            """)
    SalesTotals sumApproved(
            @Param("storeId") Long storeId,
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end
    );

    interface SalesTotals {
        Long getApprovedAmount();

        Long getRefundedAmount();
    }
}
