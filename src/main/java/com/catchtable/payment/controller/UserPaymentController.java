package com.catchtable.payment.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.payment.dto.PaymentDetailResponse;
import com.catchtable.payment.dto.PaymentListCondition;
import com.catchtable.payment.dto.PaymentListResponse;
import com.catchtable.payment.service.PaymentReconcileService;
import com.catchtable.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/payments")
public class UserPaymentController {

    private final PaymentService paymentService;
    private final PaymentReconcileService reconcileService;

    /** 내 결제 목록을 최신순으로 조회한다. */
    @GetMapping
    public PaymentListResponse getPayments(@LoginMember Long memberId,
                                           @Valid @ModelAttribute PaymentListCondition condition) {
        return paymentService.getPayments(memberId, condition);
    }

    /** 본인 결제 상세와 환불 이력을 조회하며, 남의 결제는 404다. */
    @GetMapping("/{paymentId}")
    public PaymentDetailResponse getPayment(@LoginMember Long memberId, @PathVariable Long paymentId) {
        return paymentService.getPayment(memberId, paymentId);
    }

    /** Toss 원장을 다시 조회해 내 결제·예약 상태를 보정하고 상세를 반환한다. */
    @PostMapping("/{paymentId}/sync")
    public PaymentDetailResponse sync(@LoginMember Long memberId, @PathVariable Long paymentId) {
        return reconcileService.sync(memberId, paymentId);
    }
}
