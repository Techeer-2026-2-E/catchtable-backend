-- 결제 시도 (예약·선점은 같은 reservation 행)
CREATE TABLE payment (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY,
    member_id        BIGINT       NOT NULL,
    reservation_id   BIGINT       NOT NULL,
    order_id         VARCHAR(64)  NOT NULL,
    order_name       VARCHAR(100) NOT NULL,
    amount           BIGINT       NOT NULL,
    payment_key      VARCHAR(200),
    status           VARCHAR(20)  NOT NULL,
    method           VARCHAR(20),
    approved_at      TIMESTAMPTZ,
    refunded_amount  BIGINT       NOT NULL DEFAULT 0,
    receipt_url      VARCHAR(500),
    failure_code     VARCHAR(100),
    failure_message  VARCHAR(510),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT fk_payment_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT uk_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_payment_payment_key UNIQUE (payment_key),
    CONSTRAINT chk_payment_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_status CHECK (status IN
        ('READY', 'DONE', 'FAILED', 'CANCELED', 'TIMEOUT', 'PARTIAL_REFUNDED', 'REFUNDED')),
    -- 승인 이후 상태는 승인 정보 필수
    CONSTRAINT chk_payment_approved CHECK (
        status NOT IN ('DONE', 'PARTIAL_REFUNDED', 'REFUNDED')
        OR (payment_key IS NOT NULL AND approved_at IS NOT NULL)
    ),
    -- 상태별 누적 환불액 범위
    CONSTRAINT chk_payment_refunded_amount CHECK (
        CASE status
            WHEN 'REFUNDED' THEN refunded_amount = amount
            WHEN 'PARTIAL_REFUNDED' THEN refunded_amount > 0 AND refunded_amount < amount
            ELSE refunded_amount = 0
        END
    )
);

-- 예약당 진행 중·성공 결제 1건
CREATE UNIQUE INDEX uk_payment_active_reservation ON payment (reservation_id)
    WHERE status IN ('READY', 'TIMEOUT', 'DONE', 'PARTIAL_REFUNDED');
CREATE INDEX idx_payment_member ON payment (member_id, id DESC);

CREATE TABLE payment_status_history (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY,
    payment_id       BIGINT       NOT NULL,
    previous_status  VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    source           VARCHAR(20)  NOT NULL,
    detail           VARCHAR(500) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_payment_status_history PRIMARY KEY (id),
    CONSTRAINT fk_payment_status_history_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT chk_payment_status_history_source CHECK (source IN
        ('USER', 'TOSS_CONFIRM', 'TOSS_QUERY', 'TOSS_CANCEL', 'SYSTEM'))
);

CREATE INDEX idx_payment_status_history_payment ON payment_status_history (payment_id, id);
