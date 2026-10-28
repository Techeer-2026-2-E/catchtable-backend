package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.payment.client.TossPaymentsException;
import com.catchtable.payment.client.TossPaymentsUnknownResultException;
import com.catchtable.payment.dto.PaymentResponse;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.entity.PaymentStatusSource;
import com.catchtable.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

// 결제 승인 멱등 (Toss 호출 중 행 잠금 없음, 결과는 PaymentService 잠금 메서드로 반영)
// 트랜잭션 없음: markTimeout·markFailed가 예외 응답 전에 각자 커밋
@Service
@RequiredArgsConstructor
public class PaymentApprovalService {

    // Toss 승인 멱등키 (주문당 1개)
    static final String CONFIRM_KEY_PREFIX = "confirm-";

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final TossPaymentsClient tossPaymentsClient;

    // #65 confirm 용
    public PaymentResponse approve(Long memberId, String orderId, String paymentKey, long amount) {
        // 바깥 트랜잭션에 묶이면 결과 불명 기록이 롤백되고 Toss 호출 중 잠금이 유지됨
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 승인·환불은 트랜잭션 밖에서 호출");
        }
        if (orderId == null || paymentKey == null || paymentKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Payment payment = paymentRepository.findByOrderIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getAmount() != amount) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        // 승인 재요청은 Toss 호출 없이 기존 결과
        if (payment.getStatus().isApproved()) {
            if (paymentKey.equals(payment.getPaymentKey())) {
                return PaymentResponse.from(payment);
            }
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        if (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        if (paymentRepository.existsByPaymentKeyAndIdNot(paymentKey, payment.getId())) {
            throw new BusinessException(ErrorCode.PAYMENT_KEY_ALREADY_USED);
        }

        Long paymentId = payment.getId();
        TossPaymentResponse toss;
        try {
            // TIMEOUT 재시도도 같은 키
            toss = tossPaymentsClient.confirm(paymentKey, orderId, amount, CONFIRM_KEY_PREFIX + orderId);
        } catch (TossPaymentsException exception) {
            String code = exception.getCode() == null ? "HTTP_" + exception.getHttpStatus() : exception.getCode();
            return unlessApproved(paymentId,
                    () -> paymentService.markFailed(paymentId, code, exception.getMessage(),
                            PaymentStatusSource.TOSS_CONFIRM));
        } catch (TossPaymentsUnknownResultException exception) {
            PaymentResponse current = unlessApproved(paymentId,
                    () -> paymentService.markTimeout(paymentId, "승인 결과 불명", PaymentStatusSource.TOSS_CONFIRM));
            if (current.status().isApproved()) {
                return current;
            }
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        try {
            return paymentService.applyTossPayment(paymentId, toss, PaymentStatusSource.TOSS_CONFIRM);
        } catch (DataIntegrityViolationException exception) {
            // 사전 검사 후 다른 결제가 같은 paymentKey로 먼저 승인
            throw new BusinessException(ErrorCode.PAYMENT_KEY_ALREADY_USED);
        }
    }

    // 동시 요청이 먼저 승인했으면 현재 결과
    private PaymentResponse unlessApproved(Long paymentId, Supplier<PaymentResponse> change) {
        try {
            return change.get();
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.INVALID_PAYMENT_STATUS) {
                throw exception;
            }
            Payment current = paymentRepository.findById(paymentId).orElseThrow(() -> exception);
            if (!current.getStatus().isApproved()) {
                throw exception;
            }
            return PaymentResponse.from(current);
        }
    }
}
