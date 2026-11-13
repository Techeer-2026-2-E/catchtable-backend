package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.payment.client.TossPaymentsException;
import com.catchtable.payment.client.TossPaymentsUnknownResultException;
import com.catchtable.payment.dto.PaymentDetailResponse;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefund;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.entity.PaymentStatusSource;
import com.catchtable.payment.repository.PaymentReconcileJobRepository;
import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.reservation.service.ReservationPaymentService;
import com.catchtable.reservation.service.ReservationPaymentService.Settlement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// 결제 결과 재확인 (기능63, Toss 조회 중 잠금 없음, 단계별 커밋)
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconcileService {

    private static final int IDEMPOTENCY_KEY_DAYS = 15;  // Toss 멱등키 유효기간

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentService paymentService;
    private final PaymentRefundService refundService;
    private final ReservationPaymentService reservationPaymentService;
    private final TossPaymentsClient tossPaymentsClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PaymentReconcileJobRepository jobs;

    // 고객 재확인 (남의 결제 404, 고객 아니면 403)
    public PaymentDetailResponse sync(Long memberId, Long paymentId) {
        paymentService.getPayment(memberId, paymentId);
        reconcile(paymentId);
        return paymentService.getPayment(memberId, paymentId);
    }

    // The body is an untrusted identifier hint, never a state update. No Toss signature contract exists here.
    public void handleWebhook(String body) {
        if (body == null || body.isBlank()) return;
        JsonNode root;
        try { root = objectMapper.readTree(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return; }
        if (root == null || !root.isObject()) return;
        JsonNode data = root.path("data").isObject() ? root.path("data") : root;
        String orderId = text(data, "orderId");
        String paymentKey = text(data, "paymentKey");
        Optional<Payment> payment = orderId != null ? paymentRepository.findByOrderId(orderId)
                : paymentKey != null ? paymentRepository.findByPaymentKey(paymentKey) : Optional.empty();
        // Database failures deliberately escape: ACK only after the job commit.
        payment.ifPresent(found -> jobs.enqueue(found.getId(), OffsetDateTime.now(clock), true));
    }

    // 자동 재확인 대상 수집: 영속 job만 저장하고 PG 호출은 전용 워커로 넘긴다.
    public int reconcileTargets(int limit) {
        List<Long> targets = paymentRepository.findReconcileTargets(limit);
        for (Long paymentId : targets) {
            try {
                jobs.enqueue(paymentId, OffsetDateTime.now(clock), false);
            } catch (RuntimeException exception) {
                log.warn("결제 재확인 실패 paymentId={}: {}", paymentId, exception.getMessage());
            }
        }
        return targets.size();
    }

    // Toss 원장 기준 결제·환불·예약 보정 (반복해도 같은 결과)
    public boolean reconcile(Long paymentId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 재확인은 트랜잭션 밖에서 호출");
        }
        Target target = transactionTemplate.execute(status -> target(paymentId));
        // 승인 전·결과 불명·진행 중 환불만 Toss 조회
        if (target.unresolved() || target.refundId() != null) {
            TossPaymentResponse toss = query(target);
            if (toss == null) {
                return false;
            }
            if (target.refundId() != null && toss.paymentStatus().isApproved()) {
                recoverRefund(target, toss);
            } else {
                paymentService.applyTossPayment(paymentId, toss, PaymentStatusSource.TOSS_QUERY);
            }
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (reservationPaymentService.settle(paymentId) == Settlement.LATE_SUCCESS
                    && paymentRepository.findById(paymentId).orElseThrow().getStatus() != PaymentStatus.REFUNDED) {
                paymentService.requireRefund(paymentId, "예약 확정 없이 확인된 승인");
            }
        });
        Target remaining = transactionTemplate.execute(status -> target(paymentId));
        return !remaining.unresolved() && remaining.refundId() == null;
    }

    private Target target(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        PaymentRefund refund = refundRepository.findAllByPaymentIdOrderByIdAsc(paymentId).stream()
                .filter(found -> found.getStatus().isInFlight())
                .findFirst().orElse(null);
        String paymentKey = payment.getPaymentKey();
        var reservation = payment.getReservation();
        String requestedKey = reservation == null ? null : reservation.getApprovalPaymentKey();
        // 승인 응답이 유실돼도 예약에 사전 저장한 같은 요청의 키로 조회한다.
        if (paymentKey == null && requestedKey != null && !requestedKey.isBlank()
                && reservation.matchesPaymentApproval(paymentId, payment.getOrderId(), requestedKey)) {
            paymentKey = requestedKey;
        }
        boolean unresolved = payment.getStatus() == PaymentStatus.READY || payment.getStatus() == PaymentStatus.TIMEOUT;
        return new Target(paymentId, payment.getMember().getId(), payment.getOrderId(), paymentKey,
                unresolved, refund == null ? null : refund.getId());
    }

    // 조회 실패면 null (상태 유지, 다음 재확인 대상)
    private TossPaymentResponse query(Target target) {
        try {
            return target.paymentKey() == null
                    ? tossPaymentsClient.getByOrderId(target.orderId())
                    : tossPaymentsClient.getByPaymentKey(target.paymentKey());
        } catch (TossPaymentsException | TossPaymentsUnknownResultException exception) {
            log.warn("결제 재확인 조회 실패 paymentId={}: {}", target.paymentId(), exception.getMessage());
            return null;
        }
    }

    // 진행 중 환불: Toss 누적 취소액으로 반영 여부 판단, 미반영은 15일 안 같은 키 재시도
    private void recoverRefund(Target target, TossPaymentResponse toss) {
        Retry retry = transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findByIdForUpdate(target.paymentId()).orElseThrow();
            PaymentRefund refund = refundRepository.findById(target.refundId()).orElseThrow();
            if (!refund.getStatus().isInFlight()) {
                return null;
            }
            long expected = payment.getRefundedAmount() + refund.getRefundAmount();
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (toss.canceledAmount() >= expected) {
                paymentService.applyTossPayment(target.paymentId(), toss, PaymentStatusSource.TOSS_QUERY);
                refund.complete(payment.getRefundedAmount(), now);
                return null;
            }
            if (!refund.getRequestedAt().plusDays(IDEMPOTENCY_KEY_DAYS).isAfter(now)) {
                refund.fail("IDEMPOTENCY_KEY_EXPIRED", "멱등키 15일 경과, Toss 미반영");
                return null;
            }
            return new Retry(refund.getIdempotencyKey(), refund.getCancelReason(), refund.getRequestedAmount());
        });
        if (retry == null) {
            return;
        }
        try {
            // 같은 키 재요청 경로 (정책 한도는 최초 요청 기준이라 미사용)
            refundService.refund(target.memberId(), target.paymentId(), retry.idempotencyKey(), retry.reason(),
                    retry.requestedAmount(), 0);
        } catch (BusinessException exception) {
            log.warn("환불 재시도 실패 paymentId={}: {}", target.paymentId(), exception.getErrorCode());
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private record Target(Long paymentId, Long memberId, String orderId, String paymentKey, boolean unresolved,
                          Long refundId) {
    }

    private record Retry(String idempotencyKey, String reason, Long requestedAmount) {
    }
}
