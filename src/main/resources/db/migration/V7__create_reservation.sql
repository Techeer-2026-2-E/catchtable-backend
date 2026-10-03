-- V6 예약 정책 마이그레이션 적용 후 실행된다.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE store_table
    ADD CONSTRAINT uk_store_table_id_store UNIQUE (id, store_id);

CREATE INDEX idx_store_table_active_capacity
    ON store_table (store_id, capacity, id)
    WHERE status = 'ACTIVE';

CREATE TABLE reservation (
    id                       BIGINT      GENERATED ALWAYS AS IDENTITY,
    member_id                BIGINT      NOT NULL,
    store_id                 BIGINT      NOT NULL,
    table_id                 BIGINT      NOT NULL,
    reservation_start_at     TIMESTAMPTZ NOT NULL,
    reservation_end_at       TIMESTAMPTZ NOT NULL,
    party_size               INT         NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    requested_at             TIMESTAMPTZ NOT NULL,
    confirmed_at             TIMESTAMPTZ NOT NULL,
    cancelled_at             TIMESTAMPTZ,
    cancellation_reason      VARCHAR(500),
    cancellation_actor       VARCHAR(20),
    checked_in_at            TIMESTAMPTZ,
    checked_in_by_member_id  BIGINT,
    completed_at             TIMESTAMPTZ,
    no_show_at               TIMESTAMPTZ,
    no_show_reason           VARCHAR(500),
    no_show_by_member_id     BIGINT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_reservation PRIMARY KEY (id),
    CONSTRAINT fk_reservation_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_reservation_store FOREIGN KEY (store_id) REFERENCES store (id),
    -- 선택한 테이블이 예약 매장에 속하는지 DB에서도 보장한다.
    CONSTRAINT fk_reservation_table_store FOREIGN KEY (table_id, store_id)
        REFERENCES store_table (id, store_id),
    CONSTRAINT fk_reservation_checked_in_by FOREIGN KEY (checked_in_by_member_id) REFERENCES member (id),
    CONSTRAINT fk_reservation_no_show_by FOREIGN KEY (no_show_by_member_id) REFERENCES member (id),
    CONSTRAINT chk_reservation_party_size CHECK (party_size > 0),
    CONSTRAINT chk_reservation_period CHECK (reservation_start_at < reservation_end_at),
    CONSTRAINT chk_reservation_status CHECK (status IN
        ('CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT chk_reservation_cancellation_actor CHECK (
        cancellation_actor IS NULL OR cancellation_actor IN ('CUSTOMER', 'OWNER', 'SYSTEM')
    )
);

-- 취소된 예약은 슬롯을 반환한다. [start, end) 구간이라 앞 예약 종료와 다음 예약 시작은 맞닿을 수 있다.
ALTER TABLE reservation
    ADD CONSTRAINT ex_reservation_table_period
    EXCLUDE USING gist (
        table_id WITH =,
        tstzrange(reservation_start_at, reservation_end_at, '[)') WITH &&
    )
    WHERE (status <> 'CANCELLED');

CREATE INDEX idx_reservation_member_start
    ON reservation (member_id, reservation_start_at DESC, id DESC);
CREATE INDEX idx_reservation_store_start
    ON reservation (store_id, reservation_start_at);
CREATE INDEX idx_reservation_table_id
    ON reservation (table_id);
CREATE INDEX idx_reservation_checked_in_by_member_id
    ON reservation (checked_in_by_member_id);
CREATE INDEX idx_reservation_no_show_by_member_id
    ON reservation (no_show_by_member_id);
CREATE INDEX idx_reservation_completion_target
    ON reservation (reservation_end_at, id)
    WHERE status = 'CONFIRMED' AND checked_in_at IS NOT NULL;
