package com.catchtable.notification.repository;

import com.catchtable.notification.client.AlimtalkGateway.Outcome;
import com.catchtable.notification.client.AlimtalkGateway.Result;
import com.catchtable.notification.dto.AlimtalkDelivery;
import com.catchtable.waiting.entity.Waiting;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AlimtalkOutboxRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<AlimtalkDelivery> ROW = (r, n) -> new AlimtalkDelivery(
            r.getObject("id", UUID.class), r.getLong("waiting_id"), r.getLong("member_id"),
            r.getInt("call_count"), r.getInt("recall_count"), r.getString("transport_mode"), r.getString("recipient"),
            r.getString("store_name"), r.getInt("wait_number"), r.getObject("expires_at", OffsetDateTime.class),
            r.getString("status"), r.getInt("attempt_count"), r.getString("provider_message_id"),
            r.getString("last_error_code"), r.getObject("next_attempt_at", OffsetDateTime.class));

    // 호출 시점의 수신자·내용·호출 차수를 같은 업무 트랜잭션에 보관한다.
    public void enqueue(Waiting waiting, OffsetDateTime now, String mode) {
        jdbc.update("""
                INSERT INTO alimtalk_outbox(id, waiting_id, member_id, call_count, recall_count,
                    transport_mode, recipient, store_name, wait_number, expires_at, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (waiting_id, call_count, recall_count) DO NOTHING
                """, UUID.randomUUID(), waiting.getId(), waiting.getMember().getId(), waiting.getCallCount(),
                waiting.getRecallCount(), mode, waiting.getMember().getPhone(), waiting.getStore().getName(),
                waiting.getWaitNumber(), waiting.getCallExpiresAt(), now, now, now);
    }

    public List<UUID> dueSends(OffsetDateTime now, String mode) {
        return jdbc.queryForList("""
                SELECT id FROM alimtalk_outbox WHERE status = 'PENDING' AND next_attempt_at <= ? AND transport_mode = ?
                ORDER BY next_attempt_at, id LIMIT 50
                """, UUID.class, now, mode);
    }

    public List<UUID> dueChecks(OffsetDateTime now, String mode) {
        return jdbc.queryForList("""
                SELECT id FROM alimtalk_outbox WHERE status IN ('ACCEPTED', 'UNKNOWN')
                    AND provider_message_id IS NOT NULL AND next_attempt_at <= ? AND transport_mode = ?
                ORDER BY next_attempt_at, id LIMIT 50
                """, UUID.class, now, mode);
    }

    // 외부 요청 도중 서버가 종료돼도 해당 시도는 재발송하지 않는다.
    @Transactional
    public void recoverInterrupted(OffsetDateTime now) {
        jdbc.update("""
                UPDATE alimtalk_outbox SET status = 'UNKNOWN', last_error_code = 'PROCESS_INTERRUPTED',
                    updated_at = ?, next_attempt_at = ?
                WHERE status = 'PROCESSING' AND updated_at <= ?
                """, now, now, now.minusSeconds(60));
    }

    @Transactional
    public void skipInactive(OffsetDateTime now) {
        jdbc.update("""
                UPDATE alimtalk_outbox o SET status = 'SKIPPED', last_error_code = 'CALL_INACTIVE', updated_at = ?
                WHERE o.status = 'PENDING' AND NOT EXISTS (
                    SELECT 1 FROM waiting w WHERE w.id = o.waiting_id AND w.status = 'CALLED'
                        AND w.call_count = o.call_count AND w.call_expires_at > ? AND o.expires_at > ?)
                """, now, now, now);
    }

    // 조건부 UPDATE에 성공한 한 작업자만 발송 권한을 얻는다.
    @Transactional
    public Optional<AlimtalkDelivery> claimSend(UUID id, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE alimtalk_outbox o SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    provider_message_id = NULL, updated_at = ?
                WHERE o.id = ? AND o.status = 'PENDING' AND o.next_attempt_at <= ? AND o.attempt_count < 3
                    AND EXISTS (SELECT 1 FROM waiting w WHERE w.id = o.waiting_id AND w.status = 'CALLED'
                        AND w.call_count = o.call_count AND w.call_expires_at > ? AND o.expires_at > ?)
                RETURNING o.*
                """, ROW, now, id, now, now, now).stream().findFirst();
    }

    @Transactional
    public Optional<AlimtalkDelivery> claimCheck(UUID id, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE alimtalk_outbox SET next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('ACCEPTED', 'UNKNOWN') AND provider_message_id IS NOT NULL
                    AND next_attempt_at <= ? RETURNING *
                """, ROW, now.plusSeconds(30), now, id, now).stream().findFirst();
    }

    public Optional<AlimtalkDelivery> find(UUID id) {
        return jdbc.query("SELECT * FROM alimtalk_outbox WHERE id = ?", ROW, id).stream().findFirst();
    }

    @Transactional
    public void recordResult(UUID id, int attempt, Result result, OffsetDateTime now) {
        var found = jdbc.query("SELECT * FROM alimtalk_outbox WHERE id = ? FOR UPDATE", ROW, id)
                .stream().findFirst();
        if (found.isEmpty()) return;
        AlimtalkDelivery current = found.get();
        if (current.attemptCount() != attempt || current.terminal() || attempt == 0) return;
        // 이전 실패 웹훅의 중복 수신은 재시도 시각을 바꾸지 않는다.
        if (current.status().equals("PENDING") && result.outcome() != Outcome.DELIVERED) return;
        if (current.providerMessageId() != null && result.messageId() != null
                && !current.providerMessageId().equals(result.messageId())) return;
        String messageId = result.messageId() == null ? current.providerMessageId() : result.messageId();
        String status;
        OffsetDateTime next = now.plusSeconds(30);
        switch (result.outcome()) {
            case DELIVERED -> status = "SENT";
            case ACCEPTED -> status = messageId == null ? "UNKNOWN" : "ACCEPTED";
            case UNKNOWN -> status = "UNKNOWN";
            case PERMANENT_FAILURE -> status = "FAILED";
            case RETRYABLE_FAILURE -> {
                boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM waiting WHERE id = ? AND status = 'CALLED'
                            AND call_count = ? AND call_expires_at > ?)
                        """, Boolean.class, current.waitingId(), current.callCount(), now));
                if (!active || !current.expiresAt().isAfter(now)) status = "SKIPPED";
                else if (attempt >= 3) status = "FAILED";
                else {
                    status = "PENDING";
                    next = now.plusSeconds(attempt == 1 ? 30 : 120);
                }
            }
            default -> throw new IllegalStateException("Unknown delivery outcome");
        }
        String error = result.errorCode();
        if (error != null && !error.matches("[A-Z0-9_]{1,64}")) error = "PROVIDER_ERROR";
        jdbc.update("""
                UPDATE alimtalk_outbox SET status = ?, provider_message_id = ?, last_error_code = ?,
                    next_attempt_at = ?, updated_at = ? WHERE id = ?
                """, status, messageId, error, next, now, id);
    }
}
