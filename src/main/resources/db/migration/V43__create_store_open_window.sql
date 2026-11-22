-- 날짜별 영업 구간 (날짜·시간 검색용 미리 계산)
-- 영업하는 날만 행이 있다. 특정일 설정 > 요일별 설정, 특정일 휴무는 행 없음
-- 시각은 business_date 기준 timestamp (자정 넘김은 +1일)
CREATE TABLE store_open_window (
    store_id        BIGINT    NOT NULL,
    business_date   DATE      NOT NULL,
    opens_at        TIMESTAMP NOT NULL,
    closes_at       TIMESTAMP NOT NULL,
    break_starts_at TIMESTAMP,
    break_ends_at   TIMESTAMP,
    -- store FK 없음: 전체 계산의 FK 확인(매장 행 KEY SHARE)이 영업시간 수정의 매장 행 잠금·트리거와 교착될 수 있음
    CONSTRAINT pk_store_open_window PRIMARY KEY (store_id, business_date)
);

CREATE INDEX idx_store_open_window_date ON store_open_window (business_date, store_id);

-- 계산이 끝난 날짜. 여기 있는 날짜만 store_open_window를 믿는다
CREATE TABLE store_open_window_date (
    business_date DATE        NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_store_open_window_date PRIMARY KEY (business_date)
);

-- 영업 구간 계산 (기존 검색 SQL의 영업 판단 규칙 그대로). p_store_ids NULL → 전체 매장
CREATE FUNCTION refresh_store_open_window(p_store_ids BIGINT[], p_dates DATE[]) RETURNS void
LANGUAGE sql AS $$
    DELETE FROM store_open_window
    WHERE business_date = ANY (p_dates) AND (p_store_ids IS NULL OR store_id = ANY (p_store_ids));

    INSERT INTO store_open_window (store_id, business_date, opens_at, closes_at, break_starts_at, break_ends_at)
    SELECT h.store_id, h.business_date,
           h.business_date + h.open_time,
           h.business_date + h.closing_time
               + CASE WHEN h.closing_time <= h.open_time THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END,
           h.business_date + h.break_start_time
               + CASE WHEN h.break_start_time < h.open_time THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END,
           h.business_date + h.break_end_time
               + CASE WHEN h.break_end_time < h.open_time THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END
    FROM (
        SELECT sbd.store_id, sbd.business_date,
               sbd.open_time, sbd.closing_time, sbd.break_start_time, sbd.break_end_time
        FROM special_business_day sbd
        WHERE sbd.deleted_at IS NULL AND sbd.closed = FALSE
          AND sbd.business_date = ANY (p_dates)
          AND (p_store_ids IS NULL OR sbd.store_id = ANY (p_store_ids))
        UNION ALL
        SELECT bh.store_id, d.business_date,
               bh.open_time, bh.closing_time, bh.break_start_time, bh.break_end_time
        FROM unnest(p_dates) d(business_date)
        JOIN business_hour bh ON bh.day_of_week = EXTRACT(ISODOW FROM d.business_date)
        WHERE bh.deleted_at IS NULL
          AND (p_store_ids IS NULL OR bh.store_id = ANY (p_store_ids))
          AND NOT EXISTS (
              SELECT 1 FROM special_business_day sbd
              WHERE sbd.store_id = bh.store_id AND sbd.deleted_at IS NULL
                AND sbd.business_date = d.business_date
          )
    ) h;
$$;

-- 날짜 단위 전체 계산 (아직 계산 안 된 날짜만). 영업시간 변경 트리거와 배타
CREATE FUNCTION ensure_store_open_window(p_dates DATE[]) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    missing DATE[];
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('store_open_window'));
    missing := ARRAY(
        SELECT DISTINCT d FROM unnest(p_dates) d
        WHERE NOT EXISTS (SELECT 1 FROM store_open_window_date x WHERE x.business_date = d));
    IF cardinality(missing) = 0 THEN
        RETURN;
    END IF;
    PERFORM refresh_store_open_window(NULL, missing);
    INSERT INTO store_open_window_date (business_date) SELECT unnest(missing);
END;
$$;

-- 영업시간·특정일 변경 → 같은 트랜잭션에서 해당 매장의 계산된 날짜 전부 재계산
CREATE FUNCTION store_open_window_on_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    store_ids BIGINT[];
    dates DATE[];
BEGIN
    PERFORM pg_advisory_xact_lock_shared(hashtext('store_open_window'));
    IF TG_OP = 'INSERT' THEN
        store_ids := ARRAY(SELECT DISTINCT store_id FROM new_rows);
    ELSIF TG_OP = 'UPDATE' THEN
        store_ids := ARRAY(SELECT store_id FROM new_rows UNION SELECT store_id FROM old_rows);
    ELSE
        store_ids := ARRAY(SELECT DISTINCT store_id FROM old_rows);
    END IF;
    dates := ARRAY(SELECT business_date FROM store_open_window_date);
    IF cardinality(store_ids) > 0 AND cardinality(dates) > 0 THEN
        PERFORM refresh_store_open_window(store_ids, dates);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_business_hour_open_window_ins AFTER INSERT ON business_hour
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION store_open_window_on_change();
CREATE TRIGGER trg_business_hour_open_window_upd AFTER UPDATE ON business_hour
    REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION store_open_window_on_change();
CREATE TRIGGER trg_business_hour_open_window_del AFTER DELETE ON business_hour
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION store_open_window_on_change();

CREATE TRIGGER trg_special_business_day_open_window_ins AFTER INSERT ON special_business_day
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION store_open_window_on_change();
CREATE TRIGGER trg_special_business_day_open_window_upd AFTER UPDATE ON special_business_day
    REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION store_open_window_on_change();
CREATE TRIGGER trg_special_business_day_open_window_del AFTER DELETE ON special_business_day
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION store_open_window_on_change();
