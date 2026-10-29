package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.dto.PaymentResponse;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.service.PaymentApprovalService;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationPaymentService {

    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final PaymentApprovalService approvals;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // 시작 기록 커밋 → 잠금 없는 공통 승인 → 현재 시도 재검사·예약 확정 커밋.
    public PaymentResponse confirm(Long memberId, PaymentConfirmRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 승인은 트랜잭션 밖에서 호출");
        }
        validateRequest(request);
        Attempt attempt;
        try {
            attempt = transactions.execute(status -> prepare(memberId, request));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.PAYMENT_KEY_ALREADY_USED);
        }
        try {
            approvals.approve(memberId, attempt.orderId(), attempt.paymentKey(), request.amount());
        } catch (RuntimeException exception) {
            try {
                transactions.executeWithoutResult(status -> clearVerifiedFailure(memberId, attempt));
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        PaymentResponse result = transactions.execute(status -> finish(memberId, attempt, request.amount()));
        if (result.status() == PaymentStatus.READY || result.status() == PaymentStatus.TIMEOUT) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        return result;
    }

    private Attempt prepare(Long memberId, PaymentConfirmRequest request) {
        Long reservationId = payments.findReservationIdByOrderIdAndMemberId(request.orderId(), memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        Reservation reservation = lockReservation(memberId, reservationId);
        Payment payment = payments.findByOrderIdAndMemberIdForUpdate(request.orderId(), memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        if (reservation.isApprovalQuarantined()) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        Attempt attempt = new Attempt(payment.getId(), reservationId, request.orderId(), request.paymentKey());
        validatePayment(reservation, payment, attempt, request.amount());
        if (isVerifiedFailure(payment)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        if (reservation.getApprovalPaymentId() != null) {
            if (reservation.getApprovalStartedAt() == null) {
                throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
            }
            requireSameAttempt(reservation, attempt);
            // Toss 멱등키는 최초 요청부터 15일 유효하다. 기간 밖 재승인은 기능142 확인 전에는 보내지 않는다.
            if (!payment.getStatus().isApproved()
                    && !reservation.getApprovalStartedAt().plusDays(15).isAfter(OffsetDateTime.now(clock))) {
                throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
            }
        } else if (payment.getStatus() != PaymentStatus.READY) {
            // shortcut: 시작 기록이 없는 과거 승인은 기능142의 확인 계약 전에는 복구하지 않는다.
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        if ((payment.getStatus().isApproved() && payment.getStatus() != PaymentStatus.DONE)
                || (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT
                    && reservation.getStatus() != ReservationStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        if (payments.existsByPaymentKeyAndIdNot(request.paymentKey(), payment.getId())
                || reservations.existsByApprovalPaymentKeyAndIdNot(request.paymentKey(), reservationId)) {
            throw new BusinessException(ErrorCode.PAYMENT_KEY_ALREADY_USED);
        }
        reservation.beginPaymentApproval(payment.getId(), request.orderId(), request.paymentKey(),
                OffsetDateTime.now(clock));
        return attempt;
    }

    private PaymentResponse finish(Long memberId, Attempt attempt, long amount) {
        Reservation reservation = lockReservation(memberId, attempt.reservationId());
        Payment payment = payments.findByIdForUpdate(attempt.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        requireSameAttempt(reservation, attempt);
        validatePayment(reservation, payment, attempt, amount);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (payment.getStatus() == PaymentStatus.DONE) {
            if (!attempt.paymentKey().equals(payment.getPaymentKey())) {
                throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
            }
            confirmReservation(reservation, attempt.paymentId(), attempt.orderId(), attempt.paymentKey(), now);
        } else if (isVerifiedFailure(payment)) {
            releaseApproval(reservation, now);
        } else if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.TIMEOUT) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        return PaymentResponse.from(payment);
    }

    private void clearVerifiedFailure(Long memberId, Attempt attempt) {
        Reservation reservation = lockReservation(memberId, attempt.reservationId());
        Payment payment = payments.findByIdForUpdate(attempt.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        if (reservation.matchesPaymentApproval(attempt.paymentId(), attempt.orderId(), attempt.paymentKey())
                && isVerifiedFailure(payment)) {
            releaseApproval(reservation, OffsetDateTime.now(clock));
        }
    }

    // 기능63 재확인 결과의 예약 반영 (예약→결제 잠금, 반복해도 같은 결과)
    public Settlement settle(Long paymentId) {
        return transactions.execute(status -> {
            Long reservationId = payments.findReservationIdById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
            Reservation reservation = reservations.findByIdForUpdate(reservationId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
            Payment payment = payments.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
            return settle(reservation, payment, OffsetDateTime.now(clock));
        });
    }

    private Settlement settle(Reservation reservation, Payment payment, OffsetDateTime now) {
        PaymentStatus paymentStatus = payment.getStatus();
        if (paymentStatus == PaymentStatus.READY || paymentStatus == PaymentStatus.TIMEOUT) {
            return Settlement.KEPT;
        }
        if (reservation.isApprovalQuarantined()) {
            // 같은 예약의 다른 미확인 결제가 있으면 격리 유지
            if (payments.existsByReservationIdAndIdNotAndStatusIn(reservation.getId(), payment.getId(),
                    List.of(PaymentStatus.READY, PaymentStatus.TIMEOUT, PaymentStatus.DONE,
                            PaymentStatus.PARTIAL_REFUNDED))) {
                return Settlement.KEPT;
            }
            reservation.clearApprovalQuarantine();
            reservation.releaseHold(now);
            return paymentStatus.isApproved() ? Settlement.LATE_SUCCESS : Settlement.RELEASED;
        }
        boolean attempt = payment.getId().equals(reservation.getApprovalPaymentId());
        if (attempt && isVerifiedFailure(payment)) {
            releaseApproval(reservation, now);
            return Settlement.RELEASED;
        }
        if (attempt && paymentStatus == PaymentStatus.DONE
                && reservation.matchesPaymentApproval(payment.getId(), payment.getOrderId(), payment.getPaymentKey())) {
            confirmReservation(reservation, payment.getId(), payment.getOrderId(), payment.getPaymentKey(), now);
            return Settlement.CONFIRMED;
        }
        // 확정된 적 없는 예약의 승인: 확정 대신 자리 반환, 환불 대상
        if (paymentStatus.isApproved() && reservation.getConfirmedAt() == null) {
            if (attempt) {
                reservation.clearPaymentApproval();
            }
            if (reservation.getApprovalPaymentId() == null
                    && reservation.getStatus() == ReservationStatus.PENDING_PAYMENT) {
                reservation.releaseHold(now);
            }
            return Settlement.LATE_SUCCESS;
        }
        return Settlement.KEPT;
    }

    private void confirmReservation(Reservation reservation, Long paymentId, String orderId, String paymentKey,
                                    OffsetDateTime now) {
        if (reservation.confirmPayment(paymentId, orderId, paymentKey, now)) {
            events.publishEvent(new CustomerNotificationEvent(reservation.getMember().getId(),
                    NotificationType.RESERVATION_CONFIRMED, reservation.getId(), "예약이 확정되었습니다"));
        }
    }

    private void releaseApproval(Reservation reservation, OffsetDateTime now) {
        reservation.clearPaymentApproval();
        reservation.releaseHold(now);
    }

    private void validatePayment(Reservation reservation, Payment payment, Attempt attempt, long amount) {
        if (!payment.getReservation().getId().equals(reservation.getId())
                || !payment.getMember().getId().equals(reservation.getMember().getId())
                || !payment.getOrderId().equals(attempt.orderId())
                || reservation.getHoldExpiresAt() == null || reservation.getRefundPolicySnapshot() == null
                || !Long.valueOf(amount).equals(reservation.getDepositAmountSnapshot())
                || payment.getAmount() != amount) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (payment.getPaymentKey() != null && !attempt.paymentKey().equals(payment.getPaymentKey())) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
    }

    private void requireSameAttempt(Reservation reservation, Attempt attempt) {
        if (!reservation.matchesPaymentApproval(attempt.paymentId(), attempt.orderId(), attempt.paymentKey())) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
    }

    private boolean isVerifiedFailure(Payment payment) {
        return payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELED;
    }

    private Reservation lockReservation(Long memberId, Long reservationId) {
        return reservations.findByIdAndMemberIdForUpdate(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    private void validateRequest(PaymentConfirmRequest request) {
        if (request == null || request.orderId() == null || request.orderId().isBlank()
                || request.orderId().length() > 64 || request.paymentKey() == null || request.paymentKey().isBlank()
                || request.paymentKey().length() > 200 || request.amount() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private record Attempt(Long paymentId, Long reservationId, String orderId, String paymentKey) {
    }

    // 재확인 반영 결과
    public enum Settlement {
        CONFIRMED,     // 예약 확정 (이미 확정 포함)
        RELEASED,      // 확인된 실패, 자리 반환
        KEPT,          // 변경 없음 (결과 불명 등)
        LATE_SUCCESS   // 보호 없는 승인, 환불 대상
    }
}
