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

    /** 유효 식별 힌트의 재확인 job을 커밋한 뒤 200. 저장 실패는 5xx로 재전송을 요청한다. */
    @PostMapping("/payments")
    public ResponseEntity<Void> payments(@RequestBody(required = false) String body) {
        reconcileService.handleWebhook(body);
        return ResponseEntity.ok().build();
    }
}
