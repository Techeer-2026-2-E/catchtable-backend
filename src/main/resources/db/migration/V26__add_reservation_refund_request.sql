ALTER TABLE reservation ADD COLUMN refund_in_progress BOOLEAN NOT NULL DEFAULT FALSE;

-- 예약 정책/취소 의도는 공통 PG 요청보다 먼저 커밋한다.
CREATE TABLE reservation_refund_request (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    payment_id BIGINT NOT NULL REFERENCES payment(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(300) NOT NULL,
    intent VARCHAR(20) NOT NULL CHECK (intent IN ('PAYMENT_ONLY', 'CUSTOMER_CANCEL', 'OWNER_CANCEL')),
    cancel_reason VARCHAR(500) NOT NULL,
    requested_amount BIGINT,
    requested_at TIMESTAMPTZ NOT NULL,
    reservation_start_at TIMESTAMPTZ NOT NULL,
    policy_refund_limit BIGINT NOT NULL CHECK (policy_refund_limit >= 0),
    refund_amount BIGINT NOT NULL CHECK (refund_amount >= 0),
    refunded_amount_at_request BIGINT NOT NULL CHECK (refunded_amount_at_request >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED', 'UNKNOWN', 'COMPLETED', 'FAILED', 'NOT_REQUIRED')),
    total_refunded_amount BIGINT,
    completed_at TIMESTAMPTZ,
    UNIQUE (reservation_id, idempotency_key),
    UNIQUE (payment_id, idempotency_key)
);
CREATE UNIQUE INDEX uq_reservation_refund_pending ON reservation_refund_request(reservation_id)
    WHERE status IN ('REQUESTED', 'UNKNOWN');
CREATE UNIQUE INDEX uq_payment_reservation_refund_pending ON reservation_refund_request(payment_id)
    WHERE status IN ('REQUESTED', 'UNKNOWN');
