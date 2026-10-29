-- 환불 필요 표시 (예약 확정 없이 확인된 늦은 승인)
ALTER TABLE payment
    ADD COLUMN refund_required_at TIMESTAMPTZ,
    ADD COLUMN refund_required_reason VARCHAR(300),
    ADD CONSTRAINT chk_payment_refund_required CHECK (
        (refund_required_at IS NULL AND refund_required_reason IS NULL)
        OR (refund_required_at IS NOT NULL AND refund_required_reason IS NOT NULL
            AND status IN ('DONE', 'PARTIAL_REFUNDED', 'REFUNDED'))
    );
