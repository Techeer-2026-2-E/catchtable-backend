package com.catchtable.payment.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.dto.PaymentResponse;
import com.catchtable.reservation.service.ReservationPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/payments")
public class UserPaymentApprovalController {

    private final ReservationPaymentService reservationPaymentService;

    @PostMapping("/confirm")
    public PaymentResponse confirm(@LoginMember Long memberId, @Valid @RequestBody PaymentConfirmRequest request) {
        return reservationPaymentService.confirm(memberId, request);
    }
}
