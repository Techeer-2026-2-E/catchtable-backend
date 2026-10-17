-- 선점과 확정 예약을 같은 행으로 관리해 하나의 시간 중복 제약을 공유한다.
ALTER TABLE reservation
    ADD COLUMN hold_expires_at TIMESTAMPTZ,
    ALTER COLUMN confirmed_at DROP NOT NULL,
    DROP CONSTRAINT chk_reservation_status,
    DROP CONSTRAINT ex_reservation_table_period;

ALTER TABLE reservation
    ADD CONSTRAINT chk_reservation_status CHECK (status IN
        ('PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED')),
    ADD CONSTRAINT chk_reservation_hold CHECK (
        status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
        OR (hold_expires_at IS NOT NULL AND confirmed_at IS NULL)
    ),
    ADD CONSTRAINT chk_reservation_confirmed_at CHECK (
        status IN ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLED') OR confirmed_at IS NOT NULL
    ),
    ADD CONSTRAINT chk_reservation_hold_period CHECK (
        hold_expires_at IS NULL OR hold_expires_at > requested_at
    ),
    ADD CONSTRAINT ex_reservation_table_period
    EXCLUDE USING gist (
        table_id WITH =,
        tstzrange(reservation_start_at, reservation_end_at, '[)') WITH &&
    ) WHERE (status NOT IN ('CANCELLED', 'EXPIRED'));

CREATE INDEX idx_reservation_hold_expiry ON reservation (hold_expires_at, store_id)
    WHERE status = 'PENDING_PAYMENT';
