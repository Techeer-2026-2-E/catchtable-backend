-- 매장 예약금 정책 (기능 #33 P2, #241)
--   - deposit_enabled: 예약금 사용 여부
--   - deposit_amount: 예약 건당 고정 금액(원). 인원수와 무관
--   - refund_policy: FLEXIBLE·STANDARD·STRICT. 예약 생성 시 예약에 스냅샷으로 복사된다
--   - 끄면 금액 0·정책 NULL. 기존 매장은 모두 미사용으로 채워 현재 무료 동작을 유지한다
ALTER TABLE store
    ADD COLUMN deposit_enabled BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN deposit_amount  BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN refund_policy   VARCHAR(20);

ALTER TABLE store
    ADD CONSTRAINT chk_store_deposit_policy CHECK (
        (deposit_enabled AND deposit_amount > 0
            AND refund_policy IN ('FLEXIBLE', 'STANDARD', 'STRICT'))
        OR (NOT deposit_enabled AND deposit_amount = 0 AND refund_policy IS NULL)
    );
