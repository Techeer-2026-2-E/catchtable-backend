package com.catchtable.store.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 인기 점수 주기 갱신 (기본 10분)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catchtable.popularity.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class StorePopularityScheduler {

    private final StorePopularityService popularityService;

    @Scheduled(fixedDelayString = "${catchtable.popularity.refresh-interval-ms:600000}",
            initialDelayString = "${catchtable.popularity.initial-delay-ms:60000}")
    public void refresh() {
        popularityService.refresh();
    }
}
