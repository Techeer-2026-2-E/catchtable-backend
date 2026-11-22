package com.catchtable.store.search;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 1초 간격 변경 동기화 (engine=opensearch일 때만)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catchtable.search.engine", havingValue = "opensearch")
public class StoreSearchSyncWorker {

    private final StoreSearchSyncService syncService;

    @Scheduled(fixedDelayString = "${catchtable.search.sync.poll-interval-ms:1000}",
            initialDelayString = "${catchtable.search.sync.initial-delay-ms:1000}")
    public void sync() {
        while (syncService.syncDue() == StoreSearchSyncService.BATCH_SIZE) {
            // 밀린 변경이 있으면 이어서 처리
        }
    }
}
