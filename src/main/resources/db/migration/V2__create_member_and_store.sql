-- 회원 (고객 / 점주)
CREATE TABLE member (
                        id          BIGINT       GENERATED ALWAYS AS IDENTITY,
                        email       VARCHAR(100),
                        password    VARCHAR(255),
                        name        VARCHAR(50)  NOT NULL,
                        phone       VARCHAR(20)  NOT NULL,
                        user_type   VARCHAR(20)  NOT NULL,
                        created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

                        CONSTRAINT pk_member           PRIMARY KEY (id),
                        CONSTRAINT uk_member_email     UNIQUE (email),
                        CONSTRAINT chk_member_user_type CHECK (user_type IN ('CUSTOMER', 'OWNER'))
);

-- 매장
CREATE TABLE store (
                       id                                BIGINT       GENERATED ALWAYS AS IDENTITY,
                       owner_id                          BIGINT       NOT NULL,
                       name                              VARCHAR(200) NOT NULL,
                       category                          VARCHAR(50)  NOT NULL,
                       address                           VARCHAR(255) NOT NULL,
                       waiting_available                   BOOLEAN      NOT NULL DEFAULT FALSE,
                       reservation_duration_minutes      INT          NOT NULL,
                       reservation_slot_minutes INT          NOT NULL,
                       arrival_grace_minutes             INT          NOT NULL,
                       created_at                        TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at                        TIMESTAMPTZ  NOT NULL DEFAULT now(),

                       CONSTRAINT pk_store PRIMARY KEY (id),
                       CONSTRAINT fk_store_owner FOREIGN KEY (owner_id) REFERENCES member (id),
                       CONSTRAINT chk_store_category CHECK (category IN
                                                            ('KOREAN', 'JAPANESE', 'CHINESE', 'WESTERN', 'CAFE', 'BAR', 'ETC')),
                       CONSTRAINT chk_store_reservation_duration CHECK (reservation_duration_minutes > 0),
                       CONSTRAINT chk_store_slot_minutes        CHECK (reservation_slot_minutes > 0),
                       CONSTRAINT chk_store_arrival_grace        CHECK (arrival_grace_minutes >= 0)
);

-- PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다
CREATE INDEX idx_store_owner_id ON store (owner_id);

