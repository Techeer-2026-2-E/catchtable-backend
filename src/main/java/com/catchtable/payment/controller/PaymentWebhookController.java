package com.catchtable.payment.controller;

import com.catchtable.payment.service.PaymentReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/webhooks")
public class PaymentWebhookController {

    private final PaymentReconcileService reconcileService;

    /** Toss 결제 웹훅을 받아 해당 결제를 재조회하며, 본문과 처리 결과와 상관없이 200이다. */
    @PostMapping("/payments")
    public ResponseEntity<Void> payments(@RequestBody(required = false) String body) {
        reconcileService.handleWebhook(body);
        return ResponseEntity.ok().build();
    }
}
