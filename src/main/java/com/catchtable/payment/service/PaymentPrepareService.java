package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.payment.dto.PaymentPrepareRequest;
import com.catchtable.payment.dto.PaymentPrepareResponse;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.repository.PaymentStatusHistoryRepository;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentPrepareService {

    private final MemberRepository members;
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository histories;
    private final Clock clock;

    @Transactional
    public Prepared prepare(Long memberId, PaymentPrepareRequest request) {
        if (request == null || request.holdId() == null || request.holdId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        var member = members.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getUserType() != UserType.CUSTOMER) {
            throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
        }
        // 결제를 먼저 읽으면 예약 잠금을 기다리는 동안 바뀐 상태를 JPA가 재사용할 수 있다.
        Reservation reservation = reservations.findByIdAndMemberIdForUpdate(request.holdId(), memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        var existing = payments.findAllByReservationIdForUpdate(reservation.getId());
        if (existing.size() > 1) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (reservation.isApprovalQuarantined() || reservation.getApprovalPaymentId() != null
                || reservation.getApprovalStartedAt() != null
                || existing.stream().anyMatch(p -> p.getStatus() == PaymentStatus.TIMEOUT || p.getStatus().isApproved())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATUS_CHECK_REQUIRED);
        }
        if (reservation.isRefundInProgress()) {
            throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        validateHold(reservation, now);
        if (!existing.isEmpty()) {
            Payment payment = existing.getFirst();
            if (payment.getStatus() != PaymentStatus.READY) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
            }
            validateReady(reservation, payment);
            return new Prepared(PaymentPrepareResponse.from(payment, now), false);
        }
        Payment payment = payments.save(Payment.ready(member, reservation, "ct-" + UUID.randomUUID(),
                "예약금", reservation.getDepositAmountSnapshot()));
        histories.save(payment.initialHistory(now));
        return new Prepared(PaymentPrepareResponse.from(payment, now), true);
    }

    private void validateHold(Reservation reservation, OffsetDateTime now) {
        if (reservation.getDepositAmountSnapshot() == null || reservation.getDepositAmountSnapshot() <= 0
                || reservation.getRefundPolicySnapshot() == null || reservation.getHoldExpiresAt() == null
                || !reservation.getHoldExpiresAt().isEqual(reservation.getRequestedAt().plusMinutes(5))) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT || reservation.getCheckedInAt() != null
                || now.isBefore(reservation.getRequestedAt()) || !now.isBefore(reservation.getHoldExpiresAt())) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
    }

    private void validateReady(Reservation reservation, Payment payment) {
        if (!payment.getMember().getId().equals(reservation.getMember().getId())
                || !payment.getReservation().getId().equals(reservation.getId())
                || payment.getAmount() != reservation.getDepositAmountSnapshot()
                || payment.getOrderId().isBlank() || payment.getOrderName().isBlank()
                || payment.getPaymentKey() != null || payment.getApprovedAt() != null
                || payment.getMethod() != null || payment.getReceiptUrl() != null
                || payment.getFailureCode() != null || payment.getFailureMessage() != null
                || payment.getRefundedAmount() != 0 || payment.getRefundRequiredAt() != null) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
    }

    public record Prepared(PaymentPrepareResponse response, boolean created) {
    }
}
