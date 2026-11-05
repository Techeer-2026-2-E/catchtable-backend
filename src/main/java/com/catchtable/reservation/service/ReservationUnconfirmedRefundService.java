package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.reservation.dto.UnconfirmedRefundResponse;
import com.catchtable.reservation.entity.ReservationRefundIntent;
import com.catchtable.reservation.entity.ReservationRefundRequest;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRefundOutboxRepository;
import com.catchtable.reservation.repository.ReservationRefundRequestRepository;
import com.catchtable.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationUnconfirmedRefundService {
    private static final String REASON = "예약 미확정 결제 전액 환불";
    private final MemberRepository members;
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final ReservationRefundRequestRepository requests;
    private final ReservationRefundOutboxRepository outbox;
    private final ReservationRefundService policy;
    private final Clock clock;

    // PG 호출 없이 의도·동결·아웃박스만 원자적으로 커밋한다.
    @Transactional
    public Queued request(Long memberId, Long paymentId, String key) {
        if (key==null || key.isBlank() || key.length()>300) throw new BusinessException(ErrorCode.INVALID_INPUT);
        requireCustomer(memberId);
        Long reservationId=payments.findReservationIdById(paymentId)
                .orElseThrow(()->new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        var reservation=reservations.findByIdAndMemberIdForUpdate(reservationId,memberId)
                .orElseThrow(()->new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        var payment=payments.findAllByReservationIdForUpdate(reservationId).stream()
                .filter(p->p.getId().equals(paymentId) && p.getMember().getId().equals(memberId)).findFirst()
                .orElseThrow(()->new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        var existing=requests.findByReservationIdAndIdempotencyKey(reservationId,key);
        if (existing.isPresent()) {
            var request=existing.get();
            if (!request.matches(paymentId,ReservationRefundIntent.UNCONFIRMED_REFUND,REASON,request.getRequestedAmount())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (request.getStatus()==PaymentRefundStatus.FAILED) throw new BusinessException(ErrorCode.PAYMENT_REFUND_FAILED);
            return new Queued(UnconfirmedRefundResponse.from(request),false);
        }
        policy.requireNoInFlight(reservation);
        if (reservation.isApprovalQuarantined() || reservation.getApprovalPaymentId()!=null) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        if (reservation.getConfirmedAt()!=null || reservation.getCheckedInAt()!=null
                || (reservation.getStatus()!=ReservationStatus.EXPIRED && reservation.getStatus()!=ReservationStatus.CANCELLED)
                || payment.getRefundRequiredAt()==null
                || (payment.getStatus()!=PaymentStatus.DONE && payment.getStatus()!=PaymentStatus.PARTIAL_REFUNDED)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
        if (payment.getAmount()<=0 || payment.getPaymentKey()==null || payment.getPaymentKey().isBlank()
                || !Long.valueOf(payment.getAmount()).equals(reservation.getDepositAmountSnapshot())
                || reservation.getRefundPolicySnapshot()==null || reservation.getHoldExpiresAt()==null) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (refunds.findByPaymentIdAndIdempotencyKey(paymentId,key).isPresent()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        long remaining=payment.getAmount()-payment.getRefundedAmount();
        if (remaining<=0) throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        OffsetDateTime now=OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        var request=requests.saveAndFlush(ReservationRefundRequest.requested(reservation,payment,key,
                ReservationRefundIntent.UNCONFIRMED_REFUND,REASON,remaining,now,payment.getAmount(),remaining));
        reservation.beginRefund();
        outbox.enqueue(request.getId(),now);
        return new Queued(UnconfirmedRefundResponse.from(request),true);
    }

    @Transactional(readOnly=true)
    public UnconfirmedRefundResponse latest(Long memberId, Long paymentId) {
        requireCustomer(memberId);
        payments.findById(paymentId).filter(p->p.getMember().getId().equals(memberId))
                .orElseThrow(()->new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        return requests.findFirstByPaymentIdAndIntentOrderByIdDesc(paymentId,ReservationRefundIntent.UNCONFIRMED_REFUND)
                .map(UnconfirmedRefundResponse::from).orElse(null);
    }

    private void requireCustomer(Long id) {
        var member=members.findById(id).orElseThrow(()->new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getUserType()!=UserType.CUSTOMER) throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
    }
    public record Queued(UnconfirmedRefundResponse response, boolean created) {}
}
