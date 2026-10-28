package com.catchtable.payment.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.payment.dto.PaymentResponse;
import com.catchtable.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/payments")
public class UserPaymentController {

    private final PaymentService paymentService;

    /** 내 결제 목록을 최신순으로 조회한다. */
    @GetMapping
    public List<PaymentResponse> getPayments(@LoginMember Long memberId) {
        return paymentService.getPayments(memberId);
    }

    /** 본인 결제 상세를 조회하며, 남의 결제는 404다. */
    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@LoginMember Long memberId, @PathVariable Long paymentId) {
        return paymentService.getPayment(memberId, paymentId);
    }
}
