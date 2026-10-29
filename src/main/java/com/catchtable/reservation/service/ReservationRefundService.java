package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.reservation.dto.RefundPreviewResponse;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.repository.ReservationRefundRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.EnumSet;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationRefundService {

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final ReservationRefundCalculator refundCalculator;
    private final ReservationRefundRequestRepository requests;

    public RefundPreviewResponse preview(Reservation reservation, OffsetDateTime now) {
        return calculate(reservation, paymentRepository.findAllByReservation_Id(reservation.getId()),
                CancellationActor.CUSTOMER, now);
    }

    /** 호출부가 먼저 예약을 잠근다. 환불 확인 전에는 예약 취소·자리 반환을 함께 롤백한다. */
    public void requireRefundCompleted(Reservation reservation, CancellationActor actor, OffsetDateTime now) {
        var payments = paymentRepository.findAllByReservationIdForUpdate(reservation.getId());
        var refund = calculate(reservation, payments, actor, now);
        if (refund.refundableAmount() > 0) {
            throw new BusinessException(ErrorCode.REFUND_NOT_COMPLETED);
        }
    }

    public void requireNoInFlight(Reservation reservation) {
        reservation.requireNoRefundInProgress();
        if (requests.existsByReservationIdAndStatusIn(reservation.getId(),
                EnumSet.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN))
                || requests.hasCommonInFlight(reservation.getId(),
                        EnumSet.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN))) {
            throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
        }
    }

    public RefundPreviewResponse calculate(Reservation reservation, List<Payment> payments,
                                            CancellationActor actor, OffsetDateTime now) {
        reservation.requireNoRefundInProgress();
        if (payments.stream().anyMatch(payment -> paymentRefundRepository.existsByPaymentIdAndStatusIn(
                payment.getId(), EnumSet.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN)))) {
            throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
        }
        Long reservationId = reservation.getId();
        Long amount = reservation.getDepositAmountSnapshot();
        var policy = reservation.getRefundPolicySnapshot();
        // 기존 즉시 확정은 선점을 거치지 않은 무료 경로다. 결제 행이 있으면 무료로 추정하지 않는다.
        boolean legacyFree = amount == null && reservation.getHoldExpiresAt() == null;
        if ((legacyFree || Long.valueOf(0).equals(amount)) && policy == null && payments.isEmpty()) {
            return new RefundPreviewResponse(reservationId, null, "KRW", 0, 0, null,
                    reservation.getReservationStartAt(), now, 0, 0, 0, "예약금 미사용 예약");
        }
        if (amount == null || amount <= 0 || policy == null
                || payments.stream().anyMatch(payment -> payment.getStatus() == PaymentStatus.READY
                    || payment.getStatus() == PaymentStatus.TIMEOUT)) {
            throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
        }
        var approved = payments.stream().filter(payment -> payment.getStatus().isApproved()).toList();
        if (approved.size() != 1) {
            throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
        }
        var payment = approved.getFirst();
        if (!payment.getMember().getId().equals(reservation.getMember().getId()) || payment.getAmount() != amount
                || payment.getRefundedAmount() < 0 || payment.getRefundedAmount() > amount
                || (reservation.getApprovalPaymentId() != null && !reservation.matchesPaymentApproval(
                        payment.getId(), payment.getOrderId(), payment.getPaymentKey()))) {
            throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
        }
        var calculation = refundCalculator.calculate(amount, payment.getRefundedAmount(), policy,
                reservation.getReservationStartAt(), now, actor, false);
        return new RefundPreviewResponse(reservationId, payment.getId(), "KRW", amount,
                payment.getRefundedAmount(), policy, reservation.getReservationStartAt(), now,
                calculation.refundRate(), calculation.refundAmount(),
                amount - payment.getRefundedAmount() - calculation.refundAmount(),
                calculation.policyDescription());
    }
}
