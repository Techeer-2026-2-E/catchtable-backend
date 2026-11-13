package com.catchtable.payment.service;

import com.catchtable.payment.client.PaymentCapacityExceededException;
import com.catchtable.payment.repository.PaymentReconcileJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="payment.jobs.enabled", havingValue="true", matchIfMissing=true)
public class PaymentReconcileJobWorker {
    private final PaymentReconcileJobRepository jobs;
    private final PaymentReconcileService reconciler;
    private final Clock clock;
    @Value("${payment.jobs.batch-size:10}") private int batchSize;

    @Scheduled(scheduler="paymentTaskScheduler", fixedDelayString="${payment.jobs.poll-interval-ms:1000}",
            initialDelayString="${payment.jobs.initial-delay-ms:1000}")
    public void poll() {
        for (int i=0; i<batchSize; i++) {
            var claim = jobs.claim(OffsetDateTime.now(clock));
            if (claim.isEmpty()) return;
            boolean resolved = false;
            String error = null;
            try {
                resolved = reconciler.reconcile(claim.get().paymentId());
                if (!resolved) error = "RECHECK_REQUIRED";
            } catch (PaymentCapacityExceededException exception) {
                error = "CAPACITY_EXCEEDED";
            } catch (RuntimeException exception) {
                error = "RECHECK_FAILED";
                log.warn("Payment job failed paymentId={} type={}", claim.get().paymentId(),
                        exception.getClass().getSimpleName());
            }
            // If commit fails or the process stops, the lease recovers this claim after restart.
            jobs.finish(claim.get(), OffsetDateTime.now(clock), resolved, error);
        }
    }
}
