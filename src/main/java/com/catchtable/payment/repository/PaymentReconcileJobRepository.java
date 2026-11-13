package com.catchtable.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentReconcileJobRepository {
    private final JdbcTemplate jdbc;

    @Transactional
    public void enqueue(Long paymentId, OffsetDateTime now, boolean webhook) {
        jdbc.update("""
                INSERT INTO payment_reconcile_job(payment_id,next_attempt_at,created_at,updated_at)
                VALUES (?,?,?,?) ON CONFLICT (payment_id) DO UPDATE SET
                    requested_version=payment_reconcile_job.requested_version+1,
                    status=CASE WHEN payment_reconcile_job.status='DONE' THEN 'PENDING'
                                ELSE payment_reconcile_job.status END,
                    next_attempt_at=CASE WHEN payment_reconcile_job.status='DONE' THEN EXCLUDED.next_attempt_at
                                         ELSE payment_reconcile_job.next_attempt_at END,
                    updated_at=EXCLUDED.updated_at
                WHERE ? OR payment_reconcile_job.status='DONE'
                """, paymentId, now, now, now, webhook);
    }

    /** A single short transaction; no connection/row lock is retained during PG I/O. */
    @Transactional
    public Optional<Claim> claim(OffsetDateTime now) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT payment_id FROM payment_reconcile_job
                    WHERE (status='PENDING' AND next_attempt_at<=?)
                       OR (status='PROCESSING' AND lease_until<=?)
                    ORDER BY next_attempt_at,payment_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE payment_reconcile_job j SET status='PROCESSING',attempt_count=attempt_count+1,
                    claimed_version=requested_version,lease_until=?,last_attempt_at=?,updated_at=?
                FROM candidate c WHERE j.payment_id=c.payment_id
                RETURNING j.payment_id,j.attempt_count,j.claimed_version
                """, (r,n) -> new Claim(r.getLong(1),r.getLong(2),r.getLong(3)),
                now,now,now.plusSeconds(120),now,now).stream().findFirst();
    }

    @Transactional
    public void finish(Claim claim, OffsetDateTime now, boolean resolved, String error) {
        long delay = Math.min(60, 1L << Math.min(claim.attempt(), 6));
        jdbc.update("""
                UPDATE payment_reconcile_job SET
                    status=CASE WHEN ? AND requested_version=? THEN 'DONE' ELSE 'PENDING' END,
                    lease_until=NULL,next_attempt_at=?,updated_at=?,last_error_code=?
                WHERE payment_id=? AND status='PROCESSING' AND attempt_count=? AND claimed_version=?
                """, resolved,claim.version(),now.plusSeconds(resolved ? 0 : delay),now,error,
                claim.paymentId(),claim.attempt(),claim.version());
    }

    public record Claim(Long paymentId, long attempt, long version) {}
}
