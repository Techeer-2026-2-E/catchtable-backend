CREATE TABLE alimtalk_outbox (
    id UUID PRIMARY KEY,
    waiting_id BIGINT NOT NULL REFERENCES waiting(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    call_count INTEGER NOT NULL,
    recall_count INTEGER NOT NULL,
    transport_mode VARCHAR(16) NOT NULL CHECK (transport_mode IN ('simulation', 'solapi')),
    recipient VARCHAR(20) NOT NULL,
    store_name TEXT NOT NULL,
    wait_number INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(64),
    last_error_code VARCHAR(64),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_alimtalk_call UNIQUE (waiting_id, call_count, recall_count),
    CONSTRAINT ck_alimtalk_attempt CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT ck_alimtalk_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'ACCEPTED', 'UNKNOWN', 'SENT', 'FAILED', 'SKIPPED'))
);
CREATE INDEX idx_alimtalk_due ON alimtalk_outbox(status, next_attempt_at);
CREATE UNIQUE INDEX uk_alimtalk_provider_message ON alimtalk_outbox(provider_message_id)
    WHERE provider_message_id IS NOT NULL;
