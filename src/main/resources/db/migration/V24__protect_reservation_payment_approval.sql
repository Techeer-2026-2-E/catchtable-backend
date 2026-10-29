-- V23은 V20 적용 이력 조사 후 정할 snapshot rollout을 위해 비워 둔다. V20은 변경하지 않는다.
ALTER TABLE reservation
    ADD COLUMN approval_started_at TIMESTAMPTZ,
    ADD COLUMN approval_payment_id BIGINT REFERENCES payment(id),
    ADD COLUMN approval_order_id VARCHAR(64),
    ADD COLUMN approval_payment_key VARCHAR(200),
    ADD COLUMN approval_quarantined BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uk_reservation_approval_payment UNIQUE (approval_payment_id),
    ADD CONSTRAINT uk_reservation_approval_key UNIQUE (approval_payment_key),
    ADD CONSTRAINT chk_reservation_payment_approval CHECK (
        (approval_started_at IS NULL AND approval_payment_id IS NULL
            AND approval_order_id IS NULL AND approval_payment_key IS NULL)
        OR (approval_started_at IS NOT NULL AND approval_payment_id IS NOT NULL
            AND approval_order_id IS NOT NULL AND length(trim(approval_order_id)) > 0
            AND approval_payment_key IS NOT NULL AND length(trim(approval_payment_key)) > 0
            AND hold_expires_at IS NOT NULL
            AND approval_started_at >= requested_at AND approval_started_at < hold_expires_at
            AND NOT approval_quarantined)
    );

-- 과거 시작 시각·요청 키를 추정하지 않는다. raw pending의 불명/승인 원장만 보존하며 복구는 기능142다.
UPDATE reservation r
SET approval_quarantined = TRUE
WHERE r.status = 'PENDING_PAYMENT'
  AND EXISTS (
      SELECT 1 FROM payment p
      WHERE p.reservation_id = r.id
        AND p.status IN ('TIMEOUT', 'DONE', 'PARTIAL_REFUNDED', 'REFUNDED')
  );
