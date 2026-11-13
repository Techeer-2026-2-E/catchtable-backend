-- Append-only observations from this migration onward; no inferred historical backfill.
CREATE TABLE waiting_event (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(240) NOT NULL UNIQUE,
    waiting_id BIGINT NOT NULL REFERENCES waiting(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    call_count INTEGER,
    recall_count INTEGER,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(240) NOT NULL,
    details JSONB NOT NULL
);
CREATE INDEX idx_waiting_event_timeline ON waiting_event(waiting_id, occurred_at, id);

-- Observe the existing outbox atomically, including batch recovery/skip updates.
-- updated_at alone (e.g. polling claims or duplicate reports) is not a new result.
-- The original V32 row and delivery algorithm remain authoritative and unchanged.
CREATE FUNCTION record_waiting_alimtalk_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND
       ROW(OLD.status, OLD.attempt_count, OLD.provider_message_id, OLD.last_error_code)
       IS NOT DISTINCT FROM
       ROW(NEW.status, NEW.attempt_count, NEW.provider_message_id, NEW.last_error_code) THEN
        RETURN NEW;
    END IF;
    INSERT INTO waiting_event(event_key, waiting_id, event_type, occurred_at,
                              call_count, recall_count, source_type, source_id, details)
    VALUES ('alimtalk:' || NEW.id || ':' || gen_random_uuid(), NEW.waiting_id,
            'ALIMTALK_' || NEW.status, NEW.updated_at,
            NEW.call_count, NEW.recall_count, 'ALIMTALK_OUTBOX', NEW.id::text,
            jsonb_build_object('status', NEW.status, 'attemptCount', NEW.attempt_count,
                'transportMode', NEW.transport_mode, 'errorCode', NEW.last_error_code,
                'providerMessageIdKnown', NEW.provider_message_id IS NOT NULL,
                'expiresAt', NEW.expires_at, 'nextAttemptAt', NEW.next_attempt_at,
                'observation', 'OUTBOX_TRANSITION', 'customerReceipt', 'UNKNOWN'));
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_waiting_alimtalk_event
    AFTER INSERT OR UPDATE ON alimtalk_outbox
    FOR EACH ROW EXECUTE FUNCTION record_waiting_alimtalk_event();
