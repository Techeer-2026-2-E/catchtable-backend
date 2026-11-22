package com.catchtable.notification.job;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// 보내는 순간에 필요한 업무 데이터 조회 (대기열, 호출 상태, 매장 점주)
@Component
@RequiredArgsConstructor
public class NotificationJobTargetQuery {

    public record QueueRow(Long waitingId, Long memberId, String status) {}

    public record CallState(int callCount, String status, OffsetDateTime callExpiresAt) {}

    private final JdbcTemplate jdbc;

    // 매장·날짜의 진행 중 대기열 (순번 순서)
    public List<QueueRow> activeQueue(Long storeId, LocalDate waitingDate) {
        return jdbc.query("""
                        SELECT id, member_id, status FROM waiting
                        WHERE store_id = ? AND waiting_date = ?
                          AND status IN ('WAITING', 'CALLED', 'CONFIRMED')
                        ORDER BY queue_order, id
                        """,
                (rs, i) -> new QueueRow(rs.getLong("id"), rs.getLong("member_id"), rs.getString("status")),
                storeId, waitingDate);
    }

    // 지난 호출 거르기용 현재 상태
    public Optional<CallState> callState(Long waitingId) {
        return jdbc.query("SELECT call_count, status, call_expires_at FROM waiting WHERE id = ?",
                (rs, i) -> new CallState(rs.getInt("call_count"), rs.getString("status"),
                        rs.getObject("call_expires_at", OffsetDateTime.class)),
                waitingId).stream().findFirst();
    }

    // 점주 목록 갱신 수신자
    public Optional<Long> storeOwnerId(Long storeId) {
        return jdbc.queryForList("SELECT owner_id FROM store WHERE id = ?", Long.class, storeId)
                .stream().findFirst();
    }
}
