ALTER TABLE reservation_refund_request DROP CONSTRAINT reservation_refund_request_intent_check;
ALTER TABLE reservation_refund_request ADD CONSTRAINT ck_reservation_refund_request_intent
    CHECK (intent IN ('PAYMENT_ONLY', 'CUSTOMER_CANCEL', 'OWNER_CANCEL', 'UNCONFIRMED_REFUND'));

CREATE TABLE reservation_refund_outbox (
    request_id BIGINT PRIMARY KEY REFERENCES reservation_refund_request(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','DELIVERED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CHECK ((status = 'PROCESSING') = (lease_until IS NOT NULL)),
    CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);
CREATE INDEX ix_reservation_refund_outbox_due ON reservation_refund_outbox(next_attempt_at, request_id)
    WHERE status IN ('PENDING','PROCESSING');
