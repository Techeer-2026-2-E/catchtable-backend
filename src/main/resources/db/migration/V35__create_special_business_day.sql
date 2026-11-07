

CREATE TABLE special_business_day(

        id                BIGINT      GENERATED ALWAYS AS IDENTITY,
        store_id          BIGINT      NOT NULL,
        business_date     Date         NOT NULL,
        closed         BOOLEAN        NOT NULL,
        open_time      TIME,
        closing_time      TIME,
        break_start_time  TIME,
        break_end_time    TIME,
        created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
        deleted_at        TIMESTAMPTZ,

        CONSTRAINT pk_special_business_day        PRIMARY KEY (id),
        CONSTRAINT fk_special_business_day_store  FOREIGN KEY (store_id) REFERENCES store (id),
        CONSTRAINT chk_special_business_day_hours CHECK (
            (closed = TRUE
                AND open_time IS NULL AND closing_time IS NULL
                AND break_start_time IS NULL AND break_end_time IS NULL)
                OR
            (closed = FALSE
                AND open_time IS NOT NULL AND closing_time IS NOT NULL
                AND open_time <> closing_time
                AND (break_start_time IS NULL) = (break_end_time IS NULL))
            )
);

CREATE UNIQUE INDEX uk_special_business_day_store_date
    ON special_business_day (store_id, business_date)
    WHERE deleted_at IS NULL;
