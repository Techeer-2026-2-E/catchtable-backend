package com.catchtable.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결제 자동 재확인 (Toss 시크릿 키가 있을 때만, 테스트·로컬은 꺼짐)
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("!'${toss.secret-key:}'.isBlank()")
public class PaymentReconcileScheduler {

    private final PaymentReconcileService reconcileService;

    @Value("${payment.reconcile-batch-size:50}")
    private int batchSize;

    @Scheduled(initialDelayString = "${payment.reconcile-interval-ms:60000}",
            fixedDelayString = "${payment.reconcile-interval-ms:60000}")
    public int reconcile() {
        return reconcileService.reconcileTargets(batchSize);
    }
}
