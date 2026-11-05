package com.catchtable.reservation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ReservationRefundOutboxRepository {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long requestId, OffsetDateTime now) {
        jdbc.update("INSERT INTO reservation_refund_outbox(request_id,next_attempt_at,created_at,updated_at) VALUES (?,?,?,?)",
                requestId, now, now, now);
    }

    public List<Long> due(OffsetDateTime now) {
        return jdbc.queryForList("""
                SELECT request_id FROM reservation_refund_outbox
                WHERE (status='PENDING' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?)
                ORDER BY next_attempt_at,request_id LIMIT 50
                """,Long.class,now,now);
    }

    @Transactional
    public Optional<Delivery> claim(Long id, OffsetDateTime now) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT request_id FROM reservation_refund_outbox WHERE request_id=?
                        AND ((status='PENDING' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?))
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE reservation_refund_outbox o SET status='PROCESSING', attempt_count=attempt_count+1,
                    lease_until=?, updated_at=? FROM candidate c WHERE o.request_id=c.request_id
                RETURNING o.request_id,o.attempt_count
                """,(r,n)->new Delivery(r.getLong(1),r.getInt(2)),id,now,now,now.plusSeconds(120),now)
                .stream().findFirst();
    }

    @Transactional
    public void delivered(Delivery delivery, OffsetDateTime now) {
        jdbc.update("""
                UPDATE reservation_refund_outbox SET status='DELIVERED',lease_until=NULL,delivered_at=?,
                    updated_at=?,last_error_code=NULL WHERE request_id=? AND status='PROCESSING' AND attempt_count=?
                """,now,now,delivery.requestId(),delivery.attempt());
    }

    @Transactional
    public void retry(Delivery delivery, OffsetDateTime now) {
        long delay = Math.min(300, 5L << Math.min(delivery.attempt(),6));
        jdbc.update("""
                UPDATE reservation_refund_outbox SET status='PENDING',lease_until=NULL,next_attempt_at=?,
                    updated_at=?,last_error_code='DISPATCH_INTERRUPTED'
                WHERE request_id=? AND status='PROCESSING' AND attempt_count=?
                """,now.plusSeconds(delay),now,delivery.requestId(),delivery.attempt());
    }

    public record Delivery(Long requestId, int attempt) {}
}
