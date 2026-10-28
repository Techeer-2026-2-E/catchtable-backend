package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.dto.PaymentResponse;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.entity.PaymentStatusHistory;
import com.catchtable.payment.entity.PaymentStatusSource;
import com.catchtable.payment.repository.PaymentRepository;
import com.catchtable.payment.repository.PaymentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository historyRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    public List<PaymentResponse> getPayments(Long memberId) {
        requireCustomer(memberId);
        return paymentRepository.findAllByMemberIdOrderByIdDesc(memberId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    public PaymentResponse getPayment(Long memberId, Long paymentId) {
        requireCustomer(memberId);
        return paymentRepository.findByIdAndMemberId(paymentId, memberId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    // Toss 승인·조회·취소 결과 반영 (#65 confirm, #63 재확인, #68 취소용)
    @Transactional
    public PaymentResponse applyTossPayment(Long paymentId, TossPaymentResponse toss, PaymentStatusSource source) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        // 주문·금액 위변조 차단
        if (!payment.getOrderId().equals(toss.orderId()) || payment.getAmount() != toss.totalAmount()) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        PaymentStatus target = toss.paymentStatus();
        List<PaymentStatusHistory> histories = new ArrayList<>();
        switch (target) {
            case READY, TIMEOUT -> {
                // 승인 전 상태 유지
            }
            case FAILED -> histories.add(payment.fail(
                    toss.failure() == null ? toss.status() : toss.failure().code(),
                    toss.failure() == null ? null : toss.failure().message(), source, now));
            case CANCELED -> histories.add(payment.cancel("결제 유효시간 만료", source, now));
            case DONE, PARTIAL_REFUNDED, REFUNDED -> {
                // 결과 불명 중 환불까지 진행된 경우 승인부터 반영
                histories.add(payment.approve(toss.paymentKey(), toss.method(), toss.approvedAt(),
                        toss.receiptUrl(), source, now));
                if (target != PaymentStatus.DONE) {
                    histories.add(payment.refund(toss.canceledAmount(), "Toss 취소 반영", source, now));
                }
            }
        }
        historyRepository.saveAll(histories.stream().filter(Objects::nonNull).toList());
        return PaymentResponse.from(payment);
    }

    private void requireCustomer(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getUserType() != UserType.CUSTOMER) {
            throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
        }
    }
}
