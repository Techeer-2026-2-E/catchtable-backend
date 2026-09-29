\set ON_ERROR_STOP on

BEGIN;

INSERT INTO member (id, email, password, name, phone, user_type)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 'customer@local.test', 'mock-password', '목 고객', '010-0000-0001', 'CUSTOMER'),
    (2, 'owner@local.test', 'mock-password', '목 점주', '010-0000-0002', 'OWNER')
ON CONFLICT (id) DO NOTHING;

-- 새 매장에만 기본 영업일을 넣는다. 기존 매장의 휴무일은 아래 충돌 검사에서 보존한다.
WITH inserted_store AS (
    INSERT INTO store (
        id,
        owner_id,
        name,
        category,
        address,
        waiting_available,
        reservation_duration_minutes,
        reservation_slot_minutes,
        arrival_grace_minutes
    )
    OVERRIDING SYSTEM VALUE
    VALUES (1, 2, '목 식당', 'KOREAN', '서울시 강남구', false, 120, 30, 10)
    ON CONFLICT (id) DO NOTHING
    RETURNING id
)
INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
SELECT id, day, '09:00', '22:00'
FROM inserted_store CROSS JOIN generate_series(1, 7) AS day;

INSERT INTO store_table (id, store_id, table_number, capacity, status)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, 2, 'ACTIVE'),
    (2, 1, 2, 4, 'ACTIVE'),
    (3, 1, 3, 6, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM member
        WHERE id = 1 AND email = 'customer@local.test' AND user_type = 'CUSTOMER'
    ) OR NOT EXISTS (
        SELECT 1 FROM member
        WHERE id = 2 AND email = 'owner@local.test' AND user_type = 'OWNER'
    ) OR NOT EXISTS (
        SELECT 1 FROM store
        WHERE id = 1 AND owner_id = 2 AND name = '목 식당'
          AND reservation_duration_minutes = 120 AND reservation_slot_minutes = 30
          AND arrival_grace_minutes = 10 AND booking_open_days = 30
          AND booking_deadline_minutes = 0
    ) OR (
        SELECT count(*)
        FROM (VALUES (1, 1, 2), (2, 2, 4), (3, 3, 6)) AS expected(id, table_number, capacity)
        JOIN store_table st ON st.id = expected.id AND st.store_id = 1
            AND st.table_number = expected.table_number AND st.min_capacity = 1
            AND st.capacity = expected.capacity AND st.status = 'ACTIVE'
    ) <> 3 OR (
        SELECT count(*) FROM business_hour
        WHERE store_id = 1 AND deleted_at IS NULL
          AND open_time = '09:00' AND closing_time = '22:00'
          AND break_start_time IS NULL AND break_end_time IS NULL
    ) <> 7 THEN
        RAISE EXCEPTION '기존 로컬 데이터가 목 데이터와 충돌합니다. 삽입을 롤백했습니다. 기존 데이터를 보존하고 별도의 빈 데이터베이스에서 실행하세요.';
    END IF;
END
$$;

SELECT setval(pg_get_serial_sequence('member', 'id'), (SELECT max(id) FROM member), true);
SELECT setval(pg_get_serial_sequence('store', 'id'), (SELECT max(id) FROM store), true);
SELECT setval(pg_get_serial_sequence('store_table', 'id'), (SELECT max(id) FROM store_table), true);

COMMIT;
