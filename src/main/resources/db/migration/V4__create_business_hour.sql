-- 매장 요일별 영업시간 (기능 #29)
--   - 해당 요일 row가 없으면 정기 휴무
--   - closing_time <= open_time 이면 다음 날 마감 (자정 넘김 영업). 시작한 날의 영업일로 본다
--   - 브레이크가 영업시간 안에 있는지는 자정 넘김 때문에 애플리케이션에서 검증한다
CREATE TABLE business_hour (
                               id                BIGINT      GENERATED ALWAYS AS IDENTITY,
                               store_id          BIGINT      NOT NULL,
                               day_of_week       INT         NOT NULL,
                               open_time         TIME        NOT NULL,
                               closing_time      TIME        NOT NULL,
                               break_start_time  TIME,
                               break_end_time    TIME,
                               created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                               deleted_at        TIMESTAMPTZ,

                               CONSTRAINT pk_business_hour           PRIMARY KEY (id),
                               CONSTRAINT fk_business_hour_store     FOREIGN KEY (store_id) REFERENCES store (id),
                               CONSTRAINT chk_business_hour_day      CHECK (day_of_week BETWEEN 1 AND 7),  -- 1=월 ~ 7=일 (java.time.DayOfWeek)
                               CONSTRAINT chk_business_hour_not_zero CHECK (open_time <> closing_time),
                               CONSTRAINT chk_business_hour_break    CHECK ((break_start_time IS NULL) = (break_end_time IS NULL))
);

-- 삭제되지 않은 row끼리만 매장·요일 중복 금지 (소프트 삭제된 옛 row와 충돌하지 않도록).
-- store_id로 시작하므로 FK 조회 인덱스 역할도 겸한다.
CREATE UNIQUE INDEX uk_business_hour_store_day
    ON business_hour (store_id, day_of_week)
    WHERE deleted_at IS NULL;
