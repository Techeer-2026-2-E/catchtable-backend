package com.catchtable.store.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Outbox 변경 반영: 매장 단위로 묶어 처리 시점의 DB 값으로 upsert, 없어진 매장은 삭제
@Slf4j
public class StoreSearchSyncService {

    static final int BATCH_SIZE = 100;
    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final int MAX_ERROR_LENGTH = 500;

    private final StoreSearchIndex index;
    private final StoreSearchDocuments documents;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public StoreSearchSyncService(StoreSearchIndex index, StoreSearchDocuments documents, JdbcTemplate jdbcTemplate,
                                  TransactionTemplate transactionTemplate, Clock clock) {
        this.index = index;
        this.documents = documents;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    private record Row(long id, long storeId, int attempts) {
    }

    // 처리 시각이 된 한 묶음. 가져온 행 수 반환
    public int syncDue() {
        return transactionTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<Row> rows = jdbcTemplate.query("""
                    SELECT id, store_id, attempts FROM store_search_outbox
                    WHERE next_attempt_at <= ?
                    ORDER BY next_attempt_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (rs, rowNum) -> new Row(rs.getLong("id"), rs.getLong("store_id"), rs.getInt("attempts")),
                    now, BATCH_SIZE);
            if (rows.isEmpty()) {
                return 0;
            }
            Set<Long> storeIds = new LinkedHashSet<>();
            rows.forEach(row -> storeIds.add(row.storeId()));
            try {
                List<Map<String, Object>> docs = documents.findByIds(storeIds);
                Set<Long> deleted = new LinkedHashSet<>(storeIds);
                docs.forEach(doc -> deleted.remove((Long) doc.get("id")));
                index.applyChanges(docs, List.copyOf(deleted));
                jdbcTemplate.update("DELETE FROM store_search_outbox WHERE id = ANY(?)",
                        (Object) rows.stream().map(Row::id).toArray(Long[]::new));
            } catch (RuntimeException e) {
                markFailed(rows, now, e);
            }
            return rows.size();
        });
    }

    // 재시도 간격 1초부터 두 배씩, 최대 5분
    private void markFailed(List<Row> rows, OffsetDateTime now, RuntimeException e) {
        String error = String.valueOf(e);
        String lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        jdbcTemplate.batchUpdate("UPDATE store_search_outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                rows.stream().map(row -> new Object[]{
                        row.attempts() + 1,
                        now.plusSeconds(Math.min(1L << Math.min(row.attempts(), 20), MAX_BACKOFF_SECONDS)),
                        lastError,
                        row.id()}).toList());
        log.warn("매장 검색 색인 동기화 실패, 재시도 예정: 매장 {}건, {}", rows.size(), lastError);
    }
}
