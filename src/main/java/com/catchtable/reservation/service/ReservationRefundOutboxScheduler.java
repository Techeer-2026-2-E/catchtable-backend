package com.catchtable.reservation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="reservation.refund-outbox.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class ReservationRefundOutboxScheduler {
    private final ReservationRefundOutboxDispatcher dispatcher;
    @Scheduled(scheduler="paymentTaskScheduler", fixedDelayString="${reservation.refund-outbox.poll-interval-ms:1000}",
            initialDelayString="${reservation.refund-outbox.initial-delay-ms:1000}")
    public void dispatch() { dispatcher.poll(); }
}
