package com.catchtable.store.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

// 매장 검색 색인 변경 기록 (호출한 트랜잭션에 포함, engine=opensearch일 때만)
@Component
public class StoreSearchOutbox {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;

    public StoreSearchOutbox(JdbcTemplate jdbcTemplate, Clock clock,
                             @Value("${catchtable.search.engine:postgres}") String engine) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = "opensearch".equals(engine);
    }

    public void record(long storeId) {
        if (!enabled) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbcTemplate.update("INSERT INTO store_search_outbox (store_id, next_attempt_at, created_at) VALUES (?, ?, ?)",
                storeId, now, now);
    }
}
