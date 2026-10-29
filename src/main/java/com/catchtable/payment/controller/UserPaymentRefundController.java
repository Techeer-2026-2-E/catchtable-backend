package com.catchtable.payment.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.payment.dto.PaymentCancelRequest;
import com.catchtable.payment.dto.RefundResult;
import com.catchtable.reservation.service.ReservationRefundExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/payments")
@RequiredArgsConstructor
public class UserPaymentRefundController {
    private final ReservationRefundExecutionService refunds;

    @PostMapping("/{paymentId}/cancel")
    public RefundResult cancel(@LoginMember Long memberId, @PathVariable Long paymentId,
                               @RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody PaymentCancelRequest request) {
        return refunds.refund(memberId, paymentId, key, request.cancelReason(), request.cancelAmount());
    }
}
