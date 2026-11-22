package com.catchtable.store.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 날짜별 영업 구간 하루 1번 갱신 (앱 시작 직후 1회 포함 → 비어 있으면 전체 계산)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catchtable.open-window.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class StoreOpenWindowScheduler {

    private final StoreOpenWindowService openWindowService;

    @Scheduled(fixedDelayString = "${catchtable.open-window.refresh-interval-ms:86400000}",
            initialDelayString = "${catchtable.open-window.initial-delay-ms:0}")
    public void refresh() {
        openWindowService.refreshDaily();
    }
}
