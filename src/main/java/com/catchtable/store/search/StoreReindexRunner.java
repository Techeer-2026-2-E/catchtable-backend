package com.catchtable.store.search;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 재색인이 필요할 때만 켠다
//   ./gradlew bootRun --args='--catchtable.search.reindex=true'
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catchtable.search.reindex", havingValue = "true")
public class StoreReindexRunner implements ApplicationRunner {

    private final StoreReindexService storeReindexService;

    @Override
    public void run(ApplicationArguments args) {
        storeReindexService.reindexAll();
    }
}
