-- 환불 요청 (결제별 멱등키 1건)
CREATE TABLE payment_refund (
    id                     BIGINT       GENERATED ALWAYS AS IDENTITY,
    payment_id             BIGINT       NOT NULL,
    idempotency_key        VARCHAR(300) NOT NULL,
    requested_amount       BIGINT,
    cancel_reason          VARCHAR(500) NOT NULL,
    refund_amount          BIGINT       NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    total_refunded_amount  BIGINT,
    failure_code           VARCHAR(100),
    failure_message        VARCHAR(510),
    requested_at           TIMESTAMPTZ  NOT NULL,
    completed_at           TIMESTAMPTZ,

    CONSTRAINT pk_payment_refund PRIMARY KEY (id),
    CONSTRAINT fk_payment_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT uk_payment_refund_key UNIQUE (payment_id, idempotency_key),
    CONSTRAINT chk_payment_refund_status CHECK (status IN
        ('REQUESTED', 'COMPLETED', 'NOT_REQUIRED', 'FAILED', 'UNKNOWN')),
    -- 요청 원본 금액 (생략 시 null)
    CONSTRAINT chk_payment_refund_requested_amount CHECK (requested_amount IS NULL OR requested_amount > 0),
    -- 확정 환불액
    CONSTRAINT chk_payment_refund_amount CHECK (
        CASE status WHEN 'NOT_REQUIRED' THEN refund_amount = 0 ELSE refund_amount > 0 END
    ),
    -- 처리 완료 시 누적 환불액 필수
    CONSTRAINT chk_payment_refund_total CHECK (
        status NOT IN ('COMPLETED', 'NOT_REQUIRED') OR total_refunded_amount IS NOT NULL
    ),
    CONSTRAINT chk_payment_refund_completed_at CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

-- 결제별 처리 중·결과 불명 환불 1건
CREATE UNIQUE INDEX uk_payment_refund_in_flight ON payment_refund (payment_id)
    WHERE status IN ('REQUESTED', 'UNKNOWN');
CREATE INDEX idx_payment_refund_payment ON payment_refund (payment_id, id);
