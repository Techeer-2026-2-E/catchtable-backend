package com.catchtable.reservation.service;

import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.service.PaymentReconcileService;
import com.catchtable.payment.service.PaymentRefundService;
import com.catchtable.reservation.repository.ReservationRefundOutboxRepository;
import com.catchtable.reservation.repository.ReservationRefundOutboxRepository.Delivery;
import com.catchtable.reservation.repository.ReservationRefundRequestRepository;
import com.catchtable.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ReservationRefundOutboxDispatcher {
    private final ReservationRefundOutboxRepository outbox;
    private final ReservationRefundRequestRepository requests;
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final PaymentRefundService common;
    private final PaymentReconcileService reconciler;
    private final ReservationRefundSettlementService settlement;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("환불 아웃박스 처리는 트랜잭션 밖에서 호출");
        }
        for (Long id:outbox.due(now())) outbox.claim(id,now()).ifPresent(this::dispatch);
    }

    private void dispatch(Delivery delivery) {
        Attempt attempt;
        try {
            attempt=transactions.execute(tx-> {
                var found=requests.findById(delivery.requestId());
                if (found.isEmpty() || !found.get().getStatus().isInFlight()) return null;
                var request=found.get();
                var payment=request.getPayment();
                return new Attempt(request.getReservation().getId(),payment.getId(),payment.getMember().getId(),
                        request.getIdempotencyKey(),request.getCancelReason(),request.getRequestedAmount(),
                        request.getPolicyRefundLimit(),refunds.findByPaymentIdAndIdempotencyKey(payment.getId(),request.getIdempotencyKey()).isPresent());
            });
        } catch (RuntimeException exception) {
            outbox.retry(delivery,now()); return;
        }
        if (attempt==null) { outbox.delivered(delivery,now()); return; }
        try {
            // 공통 요청 저장 뒤 중단됐으면 기존 조회·같은 키·15일 복구 계약을 사용한다.
            if (attempt.commonRecorded()) reconciler.reconcile(attempt.paymentId());
            else common.refund(attempt.memberId(),attempt.paymentId(),attempt.key(),attempt.reason(),attempt.amount(),attempt.limit());
        } catch (RuntimeException exception) {
            // 외부 결과를 추정하지 않고 아래의 영속 기록으로 인계 여부를 판단한다.
        }
        try {
            boolean recorded=Boolean.TRUE.equals(transactions.execute(tx-> {
                var reservation=reservations.findByIdForUpdate(attempt.reservationId()).orElseThrow();
                settlement.settle(reservation,payments.findAllByReservationIdForUpdate(attempt.reservationId()),now());
                return refunds.findByPaymentIdAndIdempotencyKey(attempt.paymentId(),attempt.key()).isPresent();
            }));
            // DELIVERED는 PG 결과 확정과 다르다. REQUESTED/UNKNOWN의 복구는 공통 재확인 대상에 남는다.
            if (recorded) outbox.delivered(delivery,now()); else outbox.retry(delivery,now());
        } catch (RuntimeException exception) {
            outbox.retry(delivery,now());
        }
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private record Attempt(Long reservationId,Long paymentId,Long memberId,String key,String reason,
                           Long amount,long limit,boolean commonRecorded) {}
}
