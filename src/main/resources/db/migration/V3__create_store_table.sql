-- 매장 테이블 (좌석 단위: 예약·웨이팅 배정 대상)
CREATE TABLE store_table (
                             id            BIGINT      GENERATED ALWAYS AS IDENTITY,
                             store_id      BIGINT      NOT NULL,
                             table_number  INT         NOT NULL,
                             capacity      INT         NOT NULL,
                             status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                             created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                             updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

                             CONSTRAINT pk_store_table              PRIMARY KEY (id),
                             CONSTRAINT fk_store_table_store        FOREIGN KEY (store_id) REFERENCES store (id),
                             CONSTRAINT uk_store_table_store_number UNIQUE (store_id, table_number),
                             CONSTRAINT chk_store_table_number      CHECK (table_number > 0),
                             CONSTRAINT chk_store_table_capacity    CHECK (capacity > 0),
                             CONSTRAINT chk_store_table_status      CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_store_table_store_id ON store_table (store_id);
