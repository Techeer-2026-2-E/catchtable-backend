-- One durable recheck job per payment. No payment payload or provider credentials are stored.
CREATE TABLE payment_reconcile_job (
    payment_id BIGINT PRIMARY KEY REFERENCES payment(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','DONE')),
    requested_version BIGINT NOT NULL DEFAULT 1,
    claimed_version BIGINT NOT NULL DEFAULT 0,
    attempt_count BIGINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'PROCESSING') = (lease_until IS NOT NULL)),
    CHECK (requested_version >= claimed_version AND claimed_version >= 0 AND attempt_count >= 0)
);
CREATE INDEX ix_payment_reconcile_job_due ON payment_reconcile_job(next_attempt_at, payment_id)
    WHERE status <> 'DONE';
