-- 원본을 모르는 기존 행에는 현재 매장 정책이나 임의의 금액을 채우지 않는다.
ALTER TABLE reservation
    ADD COLUMN deposit_amount_snapshot BIGINT,
    ADD COLUMN refund_policy_snapshot VARCHAR(20),
    ADD CONSTRAINT chk_reservation_deposit_snapshot CHECK (
        (deposit_amount_snapshot IS NULL AND refund_policy_snapshot IS NULL)
        OR (deposit_amount_snapshot IS NOT NULL AND deposit_amount_snapshot = 0
            AND refund_policy_snapshot IS NULL)
        OR (deposit_amount_snapshot IS NOT NULL AND deposit_amount_snapshot > 0
            AND refund_policy_snapshot IS NOT NULL
            AND refund_policy_snapshot IN ('FLEXIBLE', 'STANDARD', 'STRICT'))
    );
