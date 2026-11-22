-- 실시간 알림 현황 측정용 시드. catchtable_loadtest DB 전용 (개발·테스트 DB에 넣지 않는다).
--   docker compose exec -T postgres psql -U catchtable -d catchtable_loadtest -v N=500 -f - < scripts/notification-loadtest/seed.sql
-- N = 고객 수 (기본 500). 재실행해도 같은 id에 덮어쓰지 않는다.
\set ON_ERROR_STOP on
\if :{?N}
\else
\set N 500
\endif

BEGIN;

-- 점주 1명 (id 9000), 고객 N명 (id 10001 ~ 10000+N)
INSERT INTO member (id, email, password, name, phone, user_type)
OVERRIDING SYSTEM VALUE
VALUES (9000, 'lt-owner@local.test', 'mock-password', '측정 점주', '010-9000-0000', 'OWNER')
ON CONFLICT (id) DO NOTHING;

INSERT INTO member (id, email, password, name, phone, user_type)
OVERRIDING SYSTEM VALUE
SELECT 10000 + i,
       'lt-customer-' || i || '@local.test',
       'mock-password',
       '측정 고객 ' || i,
       '010-' || lpad((i / 10000)::text, 4, '0') || '-' || lpad((i % 10000)::text, 4, '0'),
       'CUSTOMER'
FROM generate_series(1, :N) AS i
ON CONFLICT (id) DO NOTHING;

-- 매장 1개 (id 9000). 웨이팅 접수 중, 영업시간은 종일이라 실행 시각에 관계없이 신청된다.
INSERT INTO store (id, owner_id, name, category, address, waiting_available,
                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes,
                   waiting_call_limit_minutes)
OVERRIDING SYSTEM VALUE
VALUES (9000, 9000, '측정 식당', 'KOREAN', '서울시 강남구', true, 120, 30, 10, 60)
ON CONFLICT (id) DO NOTHING;

INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
SELECT 9000, day, '00:00', '23:59'
FROM generate_series(1, 7) AS day
WHERE NOT EXISTS (SELECT 1 FROM business_hour WHERE store_id = 9000);

-- 시퀀스를 고정 id 뒤로 보내 이후 자동 발급과 충돌하지 않게 한다
SELECT setval(pg_get_serial_sequence('member', 'id'), GREATEST((SELECT max(id) FROM member), 1));
SELECT setval(pg_get_serial_sequence('store', 'id'), GREATEST((SELECT max(id) FROM store), 1));

COMMIT;

SELECT count(*) AS customers FROM member WHERE user_type = 'CUSTOMER' AND id > 10000;
