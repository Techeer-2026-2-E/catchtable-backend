-- Forward-only journal; V36 business/provider history remains unchanged. No backfill.
-- Retained roots are the admission unit. No automatic deletion or counter release.
CREATE TABLE waiting_sse_capacity (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    retained_roots BIGINT NOT NULL DEFAULT 0 CHECK (retained_roots >= 0)
);
INSERT INTO waiting_sse_capacity(id) VALUES (1);
CREATE TABLE waiting_sse_root (
    id UUID PRIMARY KEY,
    event_key VARCHAR(400) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('DIRECT','QUEUE')),
    waiting_id BIGINT,
    call_count INTEGER,
    recall_count INTEGER,
    store_id BIGINT,
    waiting_date DATE,
    member_id BIGINT,
    event_type VARCHAR(64),
    message JSONB,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CLAIMED','DONE','UNKNOWN')),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    expected_recipients INTEGER,
    expected_targets INTEGER,
    expanded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CHECK ((expanded_at IS NULL AND expected_targets IS NULL AND expected_recipients IS NULL)
        OR (expanded_at IS NOT NULL AND expected_targets >= 0 AND expected_recipients >= 0))
);
CREATE INDEX idx_waiting_sse_claim ON waiting_sse_root(status, lease_until, created_at);
CREATE INDEX idx_waiting_sse_business ON waiting_sse_root(waiting_id, created_at);
CREATE TABLE waiting_sse_target (
    id UUID PRIMARY KEY,
    root_id UUID NOT NULL REFERENCES waiting_sse_root(id),
    member_id BIGINT NOT NULL,
    waiting_id BIGINT,
    call_count INTEGER,
    connection_generation VARCHAR(40) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    message JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY','MARKED','SENT','FAILED','SKIPPED','UNKNOWN')),
    reason VARCHAR(80),
    UNIQUE(root_id, member_id, waiting_id, connection_generation)
);
CREATE INDEX idx_waiting_sse_target_root ON waiting_sse_target(root_id, status);
CREATE TABLE waiting_sse_attempt (
    id UUID PRIMARY KEY,
    target_id UUID NOT NULL UNIQUE REFERENCES waiting_sse_target(id),
    claim_token UUID NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('MARKED','UNKNOWN','SEND_RETURNED','IO_ERROR','ILLEGAL_STATE','RUNTIME_ERROR','CONNECTION_GONE')),
    reason VARCHAR(80),
    marked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    observed_at TIMESTAMPTZ,
    customer_receipt VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (customer_receipt = 'UNKNOWN')
);
-- Append observations are idempotent by stable event key. UNKNOWN is never erased.
CREATE TABLE waiting_sse_observation (
    id BIGSERIAL PRIMARY KEY,
    observation_key VARCHAR(160) NOT NULL UNIQUE,
    root_id UUID NOT NULL REFERENCES waiting_sse_root(id),
    target_id UUID REFERENCES waiting_sse_target(id),
    attempt_id UUID REFERENCES waiting_sse_attempt(id),
    phase VARCHAR(40) NOT NULL,
    reason VARCHAR(80),
    last_confirmed_stage VARCHAR(40) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    customer_receipt VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (customer_receipt = 'UNKNOWN')
);
CREATE INDEX idx_waiting_sse_observation_root ON waiting_sse_observation(root_id, id);
