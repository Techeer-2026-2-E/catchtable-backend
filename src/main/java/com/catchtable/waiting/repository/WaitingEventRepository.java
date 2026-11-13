package com.catchtable.waiting.repository;

import com.catchtable.waiting.dto.WaitingEventReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class WaitingEventRepository {
    private static final String INSERT = """
            INSERT INTO waiting_event(event_key, waiting_id, event_type, occurred_at,
                call_count, recall_count, source_type, source_id, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (event_key) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    // Native ON CONFLICT avoids a constraint exception marking the business transaction rollback-only.
    // Callers must reuse an event key only for the same incident, never for a new accepted command.
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(WaitingEventReference ref, String type, OffsetDateTime at,
                       String sourceType, String sourceId, Object details) {
        try {
            jdbc.update(INSERT, ref.eventKey(), ref.waitingId(), type, at, ref.callCount(), ref.recallCount(),
                    sourceType, sourceId, mapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize waiting event", e);
        }
    }

    // Serialize the complete batch before sending it. The caller owns commit/rollback and future completion.
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendBatch(List<Entry> entries) {
        List<Object[]> rows = new ArrayList<>(entries.size());
        try {
            for (Entry entry : entries) {
                var ref = entry.reference();
                rows.add(new Object[]{ref.eventKey(), ref.waitingId(), entry.type(), entry.occurredAt(),
                        ref.callCount(), ref.recallCount(), entry.sourceType(), entry.sourceId(),
                        mapper.writeValueAsString(entry.details())});
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize waiting event batch", e);
        }
        // Explicit nullable column types avoid per-row parameter metadata lookups in the JDBC driver.
        jdbc.batchUpdate(INSERT, rows, new int[]{Types.VARCHAR, Types.BIGINT, Types.VARCHAR,
                Types.TIMESTAMP_WITH_TIMEZONE, Types.INTEGER, Types.INTEGER, Types.VARCHAR,
                Types.VARCHAR, Types.VARCHAR});
    }

    public record Entry(WaitingEventReference reference, String type, OffsetDateTime occurredAt,
                        String sourceType, String sourceId, Object details) { }
}
