package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationRefundIntent;
import com.catchtable.reservation.repository.ReservationRefundRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationRefundSettlementService {
    private final ReservationRefundRequestRepository requests;
    private final PaymentRefundRepository refunds;
    private final ApplicationEventPublisher events;

    // 호출부가 예약→같은 예약의 Payment id 순으로 잠근다. 외부 호출은 하지 않는다.
    public void settle(Reservation reservation, List<Payment> payments, OffsetDateTime now) {
        var pending = requests.findFirstByReservationIdAndStatusInOrderByIdAsc(reservation.getId(),
                EnumSet.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN));
        if (pending.isEmpty()) return;
        var request = pending.get();
        Payment payment = payments.stream().filter(p -> p.getId().equals(request.getPayment().getId()))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_MISMATCH));
        var found = refunds.findByPaymentIdAndIdempotencyKey(payment.getId(), request.getIdempotencyKey());
        if (found.isEmpty()) return; // 공통 요청 저장 전 중단: 같은 요청 재시도에서 이어 간다.
        if (!payment.getMember().getId().equals(reservation.getMember().getId())
                || !Long.valueOf(payment.getAmount()).equals(reservation.getDepositAmountSnapshot())
                || (reservation.getApprovalPaymentId() != null && !reservation.matchesPaymentApproval(
                        payment.getId(), payment.getOrderId(), payment.getPaymentKey()))) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        var refund = found.get();
        if (refund.getRefundAmount() != request.getRefundAmount()
                || !refund.isSameRequest(request.getRequestedAmount(), request.getCancelReason())) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (refund.getStatus() == PaymentRefundStatus.COMPLETED
                && (refund.getTotalRefundedAmount() == null
                    || refund.getTotalRefundedAmount() < request.getRefundedAmountAtRequest() + request.getRefundAmount()
                    || refund.getTotalRefundedAmount() > request.getPolicyRefundLimit()
                    || refund.getTotalRefundedAmount() > payment.getRefundedAmount())) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        request.apply(refund);
        if (request.getStatus().isInFlight()) return;
        if (request.getStatus() == PaymentRefundStatus.COMPLETED
                && request.getIntent() != ReservationRefundIntent.PAYMENT_ONLY
                && reservation.cancelAfterRefund(request.getIntent().actor(), request.getCancelReason(), now)) {
            canceled(reservation);
        }
        reservation.finishRefund();
    }

    public void canceled(Reservation reservation) {
        String message = reservation.getCancellationActor() == com.catchtable.reservation.entity.CancellationActor.OWNER
                ? "점주가 예약을 취소했습니다. 사유: " + reservation.getCancellationReason() : "예약이 취소되었습니다";
        events.publishEvent(new CustomerNotificationEvent(reservation.getMember().getId(),
                NotificationType.RESERVATION_CANCELED, reservation.getId(), message));
    }
}
