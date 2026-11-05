package com.catchtable.payment.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.reservation.dto.UnconfirmedRefundResponse;
import com.catchtable.reservation.service.ReservationUnconfirmedRefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/payments")
@RequiredArgsConstructor
public class UserUnconfirmedRefundController {
    private final ReservationUnconfirmedRefundService refunds;

    @PostMapping("/{paymentId}/unconfirmed-refund")
    public ResponseEntity<UnconfirmedRefundResponse> request(@LoginMember Long memberId,@PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String key) {
        var queued=refunds.request(memberId,paymentId,key);
        return ResponseEntity.status(queued.created()?202:200).body(queued.response());
    }

    @GetMapping("/{paymentId}/unconfirmed-refund")
    public ResponseEntity<UnconfirmedRefundResponse> latest(@LoginMember Long memberId,@PathVariable Long paymentId) {
        var response=refunds.latest(memberId,paymentId);
        return response==null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }
}
