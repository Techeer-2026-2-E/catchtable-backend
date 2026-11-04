package com.catchtable.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alimtalk.enabled", havingValue = "true")
public class AlimtalkScheduler {
    private final AlimtalkDeliveryService deliveryService;

    @Scheduled(fixedDelayString = "${alimtalk.poll-interval-ms:1000}",
            initialDelayString = "${alimtalk.initial-delay-ms:1000}")
    public void dispatch() {
        deliveryService.poll();
    }
}
