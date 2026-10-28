package com.catchtable.store.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

// 로컬에서 한 번 적재할 때만 켠다
//   ./gradlew bootRun --args='--catchtable.store-import.path=/경로/서울시 일반음식점 인허가 정보.csv'
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catchtable.store-import.path")
public class StoreImportRunner implements ApplicationRunner {

    private final StoreImportService storeImportService;

    @Value("${catchtable.store-import.path}")
    private String path;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        storeImportService.importFrom(Path.of(path));
    }
}
