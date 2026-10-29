package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.payment.dto.RefundResult;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.service.PaymentRefundService;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationRefundIntent;
import com.catchtable.reservation.entity.ReservationRefundRequest;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRefundRequestRepository;
import com.catchtable.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class ReservationRefundExecutionService {
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final ReservationRefundRequestRepository requests;
    private final ReservationRefundService policy;
    private final ReservationRefundCalculator calculator;
    private final ReservationRefundSettlementService settlement;
    private final PaymentRefundService commonRefunds;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MemberRepository members;

    public RefundResult refund(Long memberId, Long paymentId, String key, String reason, Long amount) {
        Long requestId = execute(memberId, null, paymentId, key, reason, amount, ReservationRefundIntent.PAYMENT_ONLY);
        return transactions.execute(tx -> requests.findById(requestId).orElseThrow().result());
    }

    public ReservationResponse cancelCustomer(Long memberId, Long reservationId, String key) {
        execute(memberId, reservationId, null, key, "고객 요청", null, ReservationRefundIntent.CUSTOMER_CANCEL);
        return transactions.execute(tx -> ReservationResponse.from(reservations.findByIdAndMember_Id(reservationId,
                memberId).orElseThrow(), now()));
    }

    public OwnerReservationResponse cancelOwner(Long ownerId, Long reservationId, String key, String reason) {
        execute(ownerId, reservationId, null, key, reason, null, ReservationRefundIntent.OWNER_CANCEL);
        return transactions.execute(tx -> OwnerReservationResponse.from(reservations.findByIdAndStore_Owner_Id(
                reservationId, ownerId).orElseThrow(), now()));
    }

    private Long execute(Long memberId, Long reservationId, Long paymentId, String key, String reason, Long amount,
                         ReservationRefundIntent intent) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("예약 환불은 트랜잭션 밖에서 호출");
        }
        if (key == null || key.isBlank() || key.length() > 300 || reason == null || reason.isBlank()
                || reason.strip().length() > 500 || (amount != null && amount <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Attempt attempt = transactions.execute(tx -> prepare(memberId, reservationId, paymentId, key,
                reason.strip(), amount, intent));
        if (attempt == null) return null;
        if (attempt.failed()) throw new BusinessException(ErrorCode.PAYMENT_REFUND_FAILED);
        if (attempt.pending()) {
            try {
                commonRefunds.refund(attempt.memberId(), attempt.paymentId(), key, attempt.reason(),
                        attempt.requestedAmount(), attempt.policyLimit());
            } catch (RuntimeException exception) {
                try {
                    finish(attempt.reservationId());
                } catch (RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                throw exception;
            }
            finish(attempt.reservationId());
        }
        return attempt.requestId();
    }

    private Attempt prepare(Long memberId, Long reservationId, Long paymentId, String key, String reason,
                            Long amount, ReservationRefundIntent intent) {
        if (intent == ReservationRefundIntent.PAYMENT_ONLY) {
            var member = members.findById(memberId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
            if (member.getUserType() != UserType.CUSTOMER) {
                throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
            }
        }
        if (paymentId != null) {
            reservationId = payments.findReservationIdById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        }
        Reservation reservation = (intent == ReservationRefundIntent.OWNER_CANCEL
                ? reservations.findByIdAndOwnerIdForUpdate(reservationId, memberId)
                : reservations.findByIdAndMemberIdForUpdate(reservationId, memberId))
                .orElseThrow(() -> new BusinessException(paymentId == null
                        ? ErrorCode.RESERVATION_NOT_FOUND : ErrorCode.PAYMENT_NOT_FOUND));
        var lockedPayments = payments.findAllByReservationIdForUpdate(reservationId);
        if (paymentId != null && lockedPayments.stream().noneMatch(p -> p.getId().equals(paymentId)
                && p.getMember().getId().equals(memberId))) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        var existing = requests.findByReservationIdAndIdempotencyKey(reservationId, key);
        if (existing.isPresent()) {
            var request = existing.get();
            if (!request.matches(paymentId, intent, reason, amount)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            settlement.settle(reservation, lockedPayments, now());
            return Attempt.of(request);
        }
        policy.requireNoInFlight(reservation);
        if (reservation.getStatus() == ReservationStatus.CANCELLED && intent != ReservationRefundIntent.PAYMENT_ONLY) {
            return null;
        }
        OffsetDateTime now = now();
        if (reservation.getStatus() != ReservationStatus.CONFIRMED || reservation.getCheckedInAt() != null
                || (intent != ReservationRefundIntent.OWNER_CANCEL && !now.isBefore(reservation.getReservationStartAt()))) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        var preview = policy.calculate(reservation, lockedPayments, intent.actor(), now);
        if (paymentId != null && !paymentId.equals(preview.paymentId())) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (preview.refundableAmount() == 0 && intent != ReservationRefundIntent.PAYMENT_ONLY) {
            if (intent == ReservationRefundIntent.OWNER_CANCEL) reservation.cancelByOwner(now, reason);
            else reservation.cancelByCustomer(now);
            settlement.canceled(reservation);
            return null;
        }
        Payment payment = lockedPayments.stream().filter(p -> p.getId().equals(preview.paymentId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_DATA_NOT_READY));
        if (intent == ReservationRefundIntent.PAYMENT_ONLY && payment.getStatus() != PaymentStatus.DONE
                && payment.getStatus() != PaymentStatus.PARTIAL_REFUNDED) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
        if (refunds.findByPaymentIdAndIdempotencyKey(payment.getId(), key).isPresent()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        long refundAmount = amount == null ? preview.refundableAmount() : amount;
        if (refundAmount > preview.refundableAmount()) throw new BusinessException(ErrorCode.REFUND_AMOUNT_EXCEEDED);
        long limit = calculator.calculate(preview.paidAmount(), 0, preview.policyType(),
                reservation.getReservationStartAt(), now, intent.actor(), false).refundAmount();
        var request = requests.saveAndFlush(ReservationRefundRequest.requested(reservation, payment, key, intent,
                reason, amount, now, limit, refundAmount));
        if (refundAmount > 0) reservation.beginRefund();
        return Attempt.of(request);
    }

    private void finish(Long reservationId) {
        transactions.executeWithoutResult(tx -> {
            Reservation reservation = reservations.findByIdForUpdate(reservationId).orElseThrow();
            settlement.settle(reservation, payments.findAllByReservationIdForUpdate(reservationId), now());
        });
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private record Attempt(Long requestId, Long reservationId, Long paymentId, Long memberId, String reason,
                           Long requestedAmount, long policyLimit, boolean pending, boolean failed) {
        static Attempt of(ReservationRefundRequest request) {
            return new Attempt(request.getId(), request.getReservation().getId(), request.getPayment().getId(),
                    request.getPayment().getMember().getId(), request.getCancelReason(), request.getRequestedAmount(),
                    request.getPolicyRefundLimit(), request.getStatus().isInFlight(),
                    request.getStatus() == PaymentRefundStatus.FAILED);
        }
    }
}
