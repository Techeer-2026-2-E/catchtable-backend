package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.payment.client.TossPaymentsException;
import com.catchtable.payment.client.TossPaymentsUnknownResultException;
import com.catchtable.payment.dto.RefundResult;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefund;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.entity.PaymentStatusSource;
import com.catchtable.payment.repository.PaymentRefundRepository;
import com.catchtable.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.function.Function;

// 환불 멱등 (결제별 멱등키 1건, Toss 호출 중 행 잠금 없음)
// 단계별 트랜잭션: 요청 기록 커밋 → Toss 취소 → 결과 반영
@Service
@RequiredArgsConstructor
public class PaymentRefundService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 300;  // Toss 제한
    private static final int MAX_CANCEL_REASON_LENGTH = 500;
    private static final EnumSet<PaymentRefundStatus> IN_FLIGHT =
            EnumSet.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN);

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentService paymentService;
    private final TossPaymentsClient tossPaymentsClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // #68 cancel 용 (policyRefundLimit: 기능61 정책상 누적 환불 한도)
    public RefundResult refund(Long memberId, Long paymentId, String idempotencyKey, String cancelReason,
                               Long cancelAmount, long policyRefundLimit) {
        // 바깥 트랜잭션에 묶이면 결과 불명 기록이 롤백되고 Toss 호출 중 잠금이 유지됨
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 승인·환불은 트랜잭션 밖에서 호출");
        }
        validate(idempotencyKey, cancelReason, cancelAmount, policyRefundLimit);
        String reason = cancelReason.strip();
        Prepared prepared = transactionTemplate.execute(status ->
                prepare(memberId, paymentId, idempotencyKey, reason, cancelAmount, policyRefundLimit));
        if (prepared.result() != null) {
            return prepared.result();
        }
        return requestCancel(prepared.attempt());
    }

    private static void validate(String idempotencyKey, String cancelReason, Long cancelAmount,
                                 long policyRefundLimit) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || cancelReason == null || cancelReason.isBlank()
                || cancelReason.strip().length() > MAX_CANCEL_REASON_LENGTH
                || (cancelAmount != null && cancelAmount <= 0) || policyRefundLimit < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    // 결제 행 잠금 안에서 재요청 판단·허용액 계산·요청 기록
    private Prepared prepare(Long memberId, Long paymentId, String idempotencyKey, String reason,
                             Long cancelAmount, long policyRefundLimit) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .filter(found -> found.getMember().getId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        var existing = refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), cancelAmount, reason);
        }
        // 결과 확인 전 다른 키로 새 환불 금지
        if (refundRepository.existsByPaymentIdAndStatusIn(paymentId, IN_FLIGHT)) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        if (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.PARTIAL_REFUNDED) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
        long allowed = Math.max(0,
                Math.min(policyRefundLimit, payment.getAmount()) - payment.getRefundedAmount());
        long refundAmount = cancelAmount == null ? allowed : cancelAmount;
        if (refundAmount > allowed) {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_EXCEEDED);
        }
        OffsetDateTime now = now();
        if (refundAmount == 0) {
            PaymentRefund refund = refundRepository.save(
                    PaymentRefund.notRequired(payment, idempotencyKey, reason, now));
            return Prepared.done(RefundResult.from(refund));
        }
        PaymentRefund refund = refundRepository.save(
                PaymentRefund.requested(payment, idempotencyKey, cancelAmount, reason, refundAmount, now));
        return Prepared.attempt(Attempt.of(refund));
    }

    // 같은 키: 최초 결과 또는 같은 키로 Toss 재시도
    private static Prepared replay(PaymentRefund refund, Long cancelAmount, String reason) {
        if (!refund.isSameRequest(cancelAmount, reason)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return switch (refund.getStatus()) {
            case COMPLETED, NOT_REQUIRED -> Prepared.done(RefundResult.from(refund));
            case FAILED -> throw new BusinessException(ErrorCode.PAYMENT_REFUND_FAILED);
            case REQUESTED, UNKNOWN -> Prepared.attempt(Attempt.of(refund));
        };
    }

    private RefundResult requestCancel(Attempt attempt) {
        TossPaymentResponse toss;
        try {
            toss = tossPaymentsClient.cancel(attempt.paymentKey(), attempt.amount(), attempt.reason(),
                    attempt.idempotencyKey());
        } catch (TossPaymentsException exception) {
            RefundResult completed = update(attempt, refund -> {
                refund.fail(exception.getCode(), exception.getMessage());
                return completedResult(refund);
            });
            if (completed != null) {
                return completed;
            }
            throw new BusinessException(ErrorCode.PAYMENT_REFUND_FAILED);
        } catch (TossPaymentsUnknownResultException exception) {
            RefundResult completed = update(attempt, refund -> {
                refund.markUnknown();
                return completedResult(refund);
            });
            if (completed != null) {
                return completed;
            }
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        // 외부 환불 성공 확인 후 누적액·결제 상태 확정
        return update(attempt, refund -> {
            paymentService.applyTossPayment(attempt.paymentId(), toss, PaymentStatusSource.TOSS_CANCEL);
            refund.complete(refund.getPayment().getRefundedAmount(), now());
            return RefundResult.from(refund);
        });
    }

    // DB 저장 정밀도(마이크로초)에 맞춤, 재요청 응답과 같은 시각
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    // 동시 같은 키 요청이 먼저 확정했으면 그 결과
    private static RefundResult completedResult(PaymentRefund refund) {
        return refund.getStatus() == PaymentRefundStatus.COMPLETED ? RefundResult.from(refund) : null;
    }

    // 결제 행 잠금 안에서 환불 기록 변경
    private RefundResult update(Attempt attempt, Function<PaymentRefund, RefundResult> change) {
        return transactionTemplate.execute(status -> {
            paymentRepository.findByIdForUpdate(attempt.paymentId()).orElseThrow();
            PaymentRefund refund = refundRepository.findById(attempt.refundId()).orElseThrow();
            return change.apply(refund);
        });
    }

    private record Attempt(Long refundId, Long paymentId, String paymentKey, long amount, String reason,
                           String idempotencyKey) {
        static Attempt of(PaymentRefund refund) {
            return new Attempt(refund.getId(), refund.getPayment().getId(), refund.getPayment().getPaymentKey(),
                    refund.getRefundAmount(), refund.getCancelReason(), refund.getIdempotencyKey());
        }
    }

    // 최초 결과 또는 Toss 요청 대상
    private record Prepared(RefundResult result, Attempt attempt) {
        static Prepared done(RefundResult result) {
            return new Prepared(result, null);
        }

        static Prepared attempt(Attempt attempt) {
            return new Prepared(null, attempt);
        }
    }
}
