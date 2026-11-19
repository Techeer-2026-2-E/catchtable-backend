-- 목 입점 매장 데이터 (로컬·성능 측정 DB 전용. 운영·CI·공용 DB에는 넣지 않는다)
--
-- 공공데이터 매장 11만 곳은 건드리지 않고, 같은 store 테이블에 입점 매장을 새로 추가한다.
--   - 매장 1만 곳 (매장마다 목 점주 1명) + 테이블·영업시간·메뉴
--   - 목 고객 10만 명
--   - 방문 완료(COMPLETED) 예약 + 리뷰 약 150만 건 (예약 1건에 리뷰 1개. 리뷰 없는 예약·미래 예약은 넣지 않는다)
--
-- 분포
--   - 위치: 공공데이터 매장 하나를 뽑아 그 근처(수십 m)에 둔다. 실제 음식점 밀도를 따르고, 핫플 지역은 4배 더 자주 뽑는다
--   - 카테고리: 뽑은 공공데이터 매장의 카테고리. 양식·일식·카페는 더 자주 뽑는다
--   - 리뷰 수: 5%는 0개, 45%는 적고, 2%는 아주 많다 (롱테일). 핫플 매장은 1.5배
--   - 별점: 매장마다 기본 별점(10%는 2.6~3.5, 78%는 3.8~4.5, 12%는 4.6~4.9)을 정하고 리뷰 별점은 그 근처에서 뽑는다
--   - 예약 날짜: 최근 2년. 최근 6개월에 더 많다. 같은 테이블에서 시간이 겹치지 않는다
--
-- 실행 순서: 앱 실행(Flyway) → 공공데이터 CSV 적재 → 이 스크립트
--   docker compose exec -T postgres psql -U catchtable -d catchtable < scripts/mock-store-seed.sql
--   규모 바꾸기: psql 에 -v store_count=1000 -v review_count=150000 -v customer_count=10000 을 붙인다
--
-- - 목 계정 이메일은 mock-owner-*@local.test, mock-customer-*@local.test 이다. 이미 있으면 멈춘다 (다시 넣으려면 새 DB에서 실행)
-- - 매장 주소는 뽑은 공공데이터 매장의 주소를 그대로 쓴다. 이름·메뉴·리뷰는 템플릿으로 만든 가짜 데이터다
-- - 노트북 기준 수 분 걸린다. 예약 INSERT가 시간 겹침 제약 검사 때문에 가장 오래 걸린다

\set ON_ERROR_STOP on
\if :{?store_count}
\else
    \set store_count 10000
\endif
\if :{?review_count}
\else
    \set review_count 1500000
\endif
\if :{?customer_count}
\else
    \set customer_count 100000
\endif
\if :{?seed}
\else
    \set seed 0.42
\endif
\timing on

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM store WHERE external_id IS NOT NULL AND latitude IS NOT NULL) THEN
        RAISE EXCEPTION '공공데이터 매장이 없습니다. CSV를 먼저 적재하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM member WHERE email LIKE 'mock-owner-%@local.test' OR email LIKE 'mock-customer-%@local.test') THEN
        RAISE EXCEPTION '목 데이터가 이미 있습니다. 다시 넣으려면 새 DB에서 실행하세요.';
    END IF;
END
$$;

-- 같은 seed면 같은 데이터가 나온다
SELECT setseed(:seed);

-- 배열에서 하나 고르기 (r은 0 이상 1 미만)
CREATE FUNCTION pg_temp.mock_pick(arr TEXT[], r DOUBLE PRECISION) RETURNS TEXT
    LANGUAGE sql IMMUTABLE
AS $$ SELECT arr[1 + floor(r * array_length(arr, 1))::int] $$;

-- 0 이상 n 미만 (hashtext는 음수가 나올 수 있다)
CREATE FUNCTION pg_temp.mock_mod(h BIGINT, n BIGINT) RETURNS INT
    LANGUAGE sql IMMUTABLE
AS $$ SELECT (((h % n) + n) % n)::int $$;

-- 핫플 중심 좌표. 반경 약 1km 안의 공공데이터 매장을 더 자주 뽑는다
CREATE TEMP TABLE mock_hotspot (name TEXT, lat DOUBLE PRECISION, lng DOUBLE PRECISION) ON COMMIT DROP;
INSERT INTO mock_hotspot VALUES
    ('강남역', 37.4979, 127.0276), ('신논현', 37.5045, 127.0250), ('가로수길', 37.5205, 127.0230),
    ('압구정로데오', 37.5274, 127.0405), ('청담', 37.5243, 127.0473), ('성수', 37.5446, 127.0559),
    ('건대입구', 37.5404, 127.0692), ('잠실', 37.5133, 127.1001), ('홍대입구', 37.5572, 126.9245),
    ('연남', 37.5622, 126.9255), ('합정·망원', 37.5530, 126.9080), ('신촌', 37.5551, 126.9368),
    ('이태원', 37.5345, 126.9946), ('한남', 37.5345, 127.0030), ('용리단길', 37.5310, 126.9720),
    ('종각', 37.5702, 126.9831), ('을지로', 37.5660, 126.9910), ('익선동', 37.5740, 126.9890),
    ('서촌', 37.5790, 126.9700), ('여의도', 37.5219, 126.9245);

-- 1. 목 매장 위치·성격 정하기
CREATE TEMP TABLE mock_store ON COMMIT DROP AS
WITH src AS (
    SELECT s.category, s.address, s.latitude, s.longitude,
           EXISTS (SELECT 1 FROM mock_hotspot h
                   WHERE abs(s.latitude - h.lat) < 0.009 AND abs(s.longitude - h.lng) < 0.0113) AS hot
    FROM store s
    WHERE s.external_id IS NOT NULL AND s.latitude IS NOT NULL
), picked AS (
    -- 가중치 비복원 추출: 가중치가 클수록 앞에 올 확률이 높다
    SELECT src.*
    FROM src
    ORDER BY -ln(1 - random())
             / ((CASE WHEN hot THEN 4 ELSE 1 END)
                * CASE category WHEN 'WESTERN' THEN 2.5 WHEN 'JAPANESE' THEN 2.5 WHEN 'CAFE' THEN 2.0
                                WHEN 'CHINESE' THEN 1.2 WHEN 'BAR' THEN 1.2 WHEN 'ETC' THEN 0.6 ELSE 1.0 END)
    LIMIT :store_count
), numbered AS (
    SELECT row_number() OVER ()::int AS seq, category, address, hot,
           latitude + (random() - 0.5) * 0.0006 AS lat,     -- 남북 약 ±30m
           longitude + (random() - 0.5) * 0.00076 AS lng,   -- 동서 약 ±30m
           coalesce((regexp_match(address, '^서울특별시\s+(\S+?)구'))[1], '서울') AS gu,
           random() AS r_rating1, random() AS r_rating2, random() AS r_tier, random() AS r_count,
           random() AS r_table, random() AS r_closed, random() AS r_name1, random() AS r_name2,
           random() AS r_branch, random() AS r_waiting, random() AS r_deposit, random() AS r_menu
    FROM picked
), profiled AS (
    SELECT n.*,
           round((CASE WHEN r_rating1 < 0.10 THEN 2.6 + r_rating2 * 0.9
                       WHEN r_rating1 < 0.88 THEN 3.8 + r_rating2 * 0.7
                       ELSE 4.6 + r_rating2 * 0.3 END)::numeric, 2) AS base_rating,
           ((CASE WHEN r_tier < 0.05 THEN 0
                  WHEN r_tier < 0.50 THEN 1 + r_count * 29
                  WHEN r_tier < 0.85 THEN 30 + r_count * 170
                  WHEN r_tier < 0.98 THEN 200 + r_count * 600
                  ELSE 800 + r_count * 4200 END)
            * CASE WHEN hot THEN 1.5 ELSE 1 END)::numeric AS raw_count
    FROM numbered n
), scaled AS (
    -- 누적합으로 반올림해 리뷰 합계가 review_count와 정확히 같게 맞춘다
    SELECT p.*, sum(raw_count) OVER (ORDER BY seq) AS cum, sum(raw_count) OVER () AS total
    FROM profiled p
), sized AS (
    SELECT s.*,
           (round(cum * :review_count / total) - round((cum - raw_count) * :review_count / total))::int AS target,
           CASE category WHEN 'CAFE' THEN 11 WHEN 'BAR' THEN 4 ELSE 5 END AS slot_count,
           CASE category WHEN 'CAFE' THEN 60 WHEN 'BAR' THEN 120 ELSE 90 END AS duration,
           CASE category WHEN 'KOREAN' THEN 8 WHEN 'CHINESE' THEN 8 WHEN 'WESTERN' THEN 7 ELSE 6 END
               + floor(r_table * 3)::int AS base_tables,
           CASE WHEN r_closed < 0.3 THEN 1 + seq % 2 END AS closed_dow   -- 30%는 월 또는 화 휴무
    FROM scaled s
)
SELECT seq, category, address, hot, lat, lng, gu, base_rating, target, slot_count, duration, closed_dow,
       -- 리뷰가 많은 매장은 하루 예약을 다 받을 만큼 테이블을 늘린다
       greatest(base_tables, ceil(target * 0.0076 / slot_count)::int) AS table_count,
       r_name1, r_name2, r_branch, r_waiting, r_deposit, r_menu,
       NULL::bigint AS owner_id, NULL::bigint AS store_id
FROM sized;

-- 2. 목 점주 (매장마다 1명)
INSERT INTO member (email, password, name, phone, user_type, created_at, updated_at)
SELECT format('mock-owner-%s@local.test', lpad(seq::text, 5, '0')), 'mock-password',
       format('목 점주 %s', seq),
       format('010-8%s-%s', lpad((seq / 10000)::text, 3, '0'), lpad((seq % 10000)::text, 4, '0')),
       'OWNER', ts, ts
FROM (SELECT seq, now() - interval '3 years' + random() * interval '300 days' AS ts FROM mock_store) o;

UPDATE mock_store ms
SET owner_id = m.id
FROM member m
WHERE m.email = format('mock-owner-%s@local.test', lpad(ms.seq::text, 5, '0'));

-- 3. 목 고객
INSERT INTO member (email, password, name, phone, user_type, created_at, updated_at)
SELECT format('mock-customer-%s@local.test', lpad(i::text, 6, '0')), 'mock-password',
       pg_temp.mock_pick(ARRAY['김','이','박','최','정','강','조','윤','장','임','한','오','서','신','권','황','안','송','류','전'], r1)
           || pg_temp.mock_pick(ARRAY['민준','서연','도윤','지우','하준','서아','은우','지아','시우','하윤',
                                      '주원','수아','지호','아린','예준','지유','유준','채원','건우','윤서',
                                      '현우','다은','우진','소율','선우','예린','연우','하은','서준','민서',
                                      '지훈','수빈','준서','지민','태윤','예은','동현','유나','승민','가은'], r2),
       format('010-9%s-%s', lpad((i / 10000)::text, 3, '0'), lpad((i % 10000)::text, 4, '0')),
       'CUSTOMER', ts, ts
FROM (SELECT i, random() AS r1, random() AS r2,
             now() - interval '3 years' + random() * interval '300 days' AS ts
      FROM generate_series(1, :customer_count) AS i) c;

CREATE TEMP TABLE mock_customer ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id)::int AS idx, id
FROM member
WHERE email LIKE 'mock-customer-%@local.test';
CREATE UNIQUE INDEX ON mock_customer (idx);

-- 4. 매장
INSERT INTO store (owner_id, name, category, address, phone, latitude, longitude, waiting_available,
                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes,
                   booking_open_days, booking_deadline_minutes,
                   deposit_enabled, deposit_amount, refund_policy, description, created_at, updated_at)
SELECT ms.owner_id,
       pg_temp.mock_pick(ARRAY['온','소담','하루','모락','미각','담소','바다','한상','라온','다온',
                               '봄날','달빛','골목','마루','정원','해담','오롯','고메','이음','도담',
                               '수수','풍경','온기','숲속','두레','한결','새벽','별빛','늘봄','소복'], ms.r_name1)
           || ' '
           || CASE ms.category
                WHEN 'KOREAN'   THEN pg_temp.mock_pick(ARRAY['식당','한식당','밥상','국밥','갈비','상회','고깃집','칼국수','한정식','찜'], ms.r_name2)
                WHEN 'JAPANESE' THEN pg_temp.mock_pick(ARRAY['스시','라멘','오마카세','돈카츠','우동','소바','덮밥','텐동'], ms.r_name2)
                WHEN 'CHINESE'  THEN pg_temp.mock_pick(ARRAY['반점','중화요리','차이나','마라탕','딤섬','양꼬치','객잔','짬뽕'], ms.r_name2)
                WHEN 'WESTERN'  THEN pg_temp.mock_pick(ARRAY['비스트로','트라토리아','키친','파스타','스테이크하우스','브라세리','그릴','피자'], ms.r_name2)
                WHEN 'CAFE'     THEN pg_temp.mock_pick(ARRAY['커피','카페','베이커리','로스터스','디저트','티하우스'], ms.r_name2)
                WHEN 'BAR'      THEN pg_temp.mock_pick(ARRAY['포차','와인바','이자카야','펍','칵테일바','주점','하이볼바'], ms.r_name2)
                ELSE                 pg_temp.mock_pick(ARRAY['쌀국수','버거','샐러드','타코','커리','브런치','분식'], ms.r_name2)
              END
           || CASE WHEN ms.r_branch < 0.3 THEN ' ' || ms.gu || '점' ELSE '' END,
       ms.category, ms.address,
       format('02-%s-%s', 2000 + (ms.seq * 37) % 8000, lpad((ms.seq % 10000)::text, 4, '0')),
       ms.lat, ms.lng,
       ms.r_waiting < 0.5,
       ms.duration, 30, 10, 30, 0,
       dep.enabled,
       CASE WHEN dep.enabled THEN CASE ms.category WHEN 'WESTERN' THEN 20000 ELSE 10000 END ELSE 0 END,
       CASE WHEN dep.enabled THEN CASE ms.category WHEN 'WESTERN' THEN 'STANDARD' ELSE 'FLEXIBLE' END END,
       CASE ms.category
           WHEN 'KOREAN'   THEN ms.gu || ' 근처에서 정성껏 차린 한식을 선보입니다. 점심 특선과 저녁 단체석을 운영해요.'
           WHEN 'JAPANESE' THEN ms.gu || '의 일식 전문점. 매일 들여오는 재료로 초밥과 덮밥을 준비합니다.'
           WHEN 'CHINESE'  THEN ms.gu || '에서 사랑받는 중식당. 코스 요리와 단체 모임 예약이 가능해요.'
           WHEN 'WESTERN'  THEN ms.gu || '의 양식 레스토랑. 파스타와 스테이크, 와인 페어링을 즐겨 보세요.'
           WHEN 'CAFE'     THEN ms.gu || ' 골목의 카페. 직접 로스팅한 원두와 수제 디저트를 준비합니다.'
           WHEN 'BAR'      THEN ms.gu || '의 술집. 퇴근 후 가볍게 한잔하기 좋은 안주와 주류가 있어요.'
           ELSE ms.gu || ' 근처 음식점. 편하게 식사하기 좋은 메뉴를 준비했습니다.'
       END,
       now() - interval '2 years 30 days', now() - interval '2 years 30 days'
FROM mock_store ms
CROSS JOIN LATERAL (SELECT ms.category IN ('WESTERN', 'JAPANESE') AND ms.r_deposit < 0.3 AS enabled) dep;

UPDATE mock_store ms
SET store_id = s.id
FROM store s
WHERE s.owner_id = ms.owner_id;
CREATE UNIQUE INDEX ON mock_store (store_id);

-- 5. 테이블: 카테고리별 인원 구성을 반복해서 table_count개
INSERT INTO store_table (store_id, table_number, capacity, min_capacity, status)
SELECT ms.store_id, i + 1, cap, CASE cap WHEN 2 THEN 1 WHEN 4 THEN 2 ELSE 3 END, 'ACTIVE'
FROM mock_store ms
CROSS JOIN LATERAL generate_series(0, ms.table_count - 1) AS i
CROSS JOIN LATERAL (
    SELECT CASE ms.category
               WHEN 'CAFE' THEN (ARRAY[2, 2, 4, 2, 4, 2])[i % 6 + 1]
               WHEN 'BAR'  THEN (ARRAY[2, 4, 4, 2, 6, 4])[i % 6 + 1]
               ELSE (ARRAY[2, 4, 4, 2, 6, 4, 2, 4])[i % 8 + 1]
           END AS cap
) c;

CREATE TEMP TABLE mock_table ON COMMIT DROP AS
SELECT st.store_id, st.table_number - 1 AS idx, st.id AS table_id, st.capacity, st.min_capacity
FROM store_table st
JOIN mock_store ms ON ms.store_id = st.store_id;
CREATE UNIQUE INDEX ON mock_table (store_id, idx);

-- 6. 영업시간: 아래 예약 시작 시각이 모두 영업시간 안에 들어가게 맞췄다. closed_dow 요일은 휴무
INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time, break_start_time, break_end_time)
SELECT ms.store_id, dow, h.open_t::time, h.close_t::time, h.break_s::time, h.break_e::time
FROM mock_store ms
JOIN (VALUES ('KOREAN',   '11:00', '22:00', '15:00', '17:00'),
             ('JAPANESE', '11:30', '22:00', '15:00', '17:30'),
             ('CHINESE',  '11:00', '22:00', '15:00', '17:00'),
             ('WESTERN',  '11:30', '22:00', '15:00', '17:30'),
             ('ETC',      '11:00', '22:00', '15:00', '17:00'),
             ('CAFE',     '10:00', '22:00', NULL,    NULL),
             ('BAR',      '17:00', '02:00', NULL,    NULL))
     AS h(category, open_t, close_t, break_s, break_e) ON h.category = ms.category
CROSS JOIN generate_series(1, 7) AS dow
WHERE ms.closed_dow IS DISTINCT FROM dow;

-- 7. 메뉴: 카테고리 메뉴 중 4~7개, 매장마다 가격을 조금씩 다르게 (500원 단위)
INSERT INTO menu (store_id, name, price, description)
SELECT store_id, name, price, description
FROM (
    SELECT ms.store_id, m.name,
           (round(m.price * (0.85 + random() * 0.35) / 500) * 500)::int AS price,
           m.description,
           row_number() OVER (PARTITION BY ms.store_id ORDER BY random()) AS rn,
           4 + floor(ms.r_menu * 4)::int AS menu_count
    FROM mock_store ms
    JOIN (VALUES
        ('KOREAN', '김치찌개', 9000, '묵은지와 돼지고기를 넣고 끓인 찌개'),
        ('KOREAN', '제육볶음', 10000, '매콤한 양념에 볶은 돼지고기'),
        ('KOREAN', '된장찌개', 9000, '집된장으로 끓인 구수한 찌개'),
        ('KOREAN', '불고기 정식', 13000, '간장 불고기와 반찬 정식'),
        ('KOREAN', '해물파전', 16000, '오징어·새우를 넣은 파전'),
        ('KOREAN', '돼지갈비 (1인분)', 17000, '참숯에 구운 양념 돼지갈비'),
        ('KOREAN', '갈비탕', 14000, '푹 고아 낸 소갈비탕'),
        ('KOREAN', '보쌈 (중)', 32000, '부드럽게 삶은 수육과 김치'),
        ('JAPANESE', '모둠 초밥 (12pcs)', 22000, '오늘의 생선으로 만든 초밥'),
        ('JAPANESE', '연어 덮밥', 15000, '숙성 연어와 간장 소스'),
        ('JAPANESE', '돈카츠 정식', 13000, '두툼한 등심 돈카츠'),
        ('JAPANESE', '냉우동', 10000, '쯔유에 담근 탱탱한 면'),
        ('JAPANESE', '가라아게', 9000, '일본식 닭튀김'),
        ('JAPANESE', '돈코츠 라멘', 11000, '진한 돼지뼈 육수 라멘'),
        ('JAPANESE', '런치 오마카세', 45000, '셰프가 고르는 점심 코스'),
        ('JAPANESE', '에비 텐동', 14000, '새우튀김을 올린 덮밥'),
        ('CHINESE', '짜장면', 8000, '춘장을 볶아 만든 기본 짜장'),
        ('CHINESE', '짬뽕', 10000, '해산물이 들어간 얼큰한 국물'),
        ('CHINESE', '탕수육 (중)', 22000, '바삭한 등심 튀김과 새콤한 소스'),
        ('CHINESE', '마파두부밥', 11000, '얼얼한 두부 덮밥'),
        ('CHINESE', '깐풍기', 26000, '매콤달콤한 닭튀김'),
        ('CHINESE', '샤오롱바오 (6pcs)', 9000, '육즙 가득한 만두'),
        ('CHINESE', '마라탕', 12000, '재료를 골라 담는 얼얼한 탕'),
        ('CHINESE', '유린기', 24000, '간장 소스를 끼얹은 닭튀김'),
        ('WESTERN', '트러플 크림 파스타', 19000, '트러플 오일을 더한 크림 파스타'),
        ('WESTERN', '토마토 해산물 파스타', 18000, '새우·홍합을 넣은 토마토 파스타'),
        ('WESTERN', '마르게리타 피자', 21000, '화덕에 구운 기본 피자'),
        ('WESTERN', '채끝 스테이크', 39000, '미디엄 레어 추천'),
        ('WESTERN', '리코타 샐러드', 14000, '수제 리코타 치즈와 제철 채소'),
        ('WESTERN', '버섯 리조또', 18000, '양송이와 파르미지아노 리조또'),
        ('WESTERN', '감바스 알 아히요', 17000, '마늘 올리브오일 새우'),
        ('WESTERN', '티본 스테이크 (2인)', 89000, '나눠 먹기 좋은 티본'),
        ('CAFE', '아메리카노', 4500, '직접 로스팅한 원두'),
        ('CAFE', '카페 라떼', 5500, '고소한 우유와 에스프레소'),
        ('CAFE', '바닐라 빈 라떼', 6000, '바닐라 빈 시럽을 직접 만들어요'),
        ('CAFE', '바스크 치즈케이크', 7000, '겉은 진하고 속은 부드러운 케이크'),
        ('CAFE', '소금빵', 3500, '매일 아침 굽는 소금빵'),
        ('CAFE', '핸드드립 커피', 6500, '원두를 골라 내리는 드립 커피'),
        ('CAFE', '크로플', 6000, '크루아상 생지로 구운 와플'),
        ('CAFE', '말차 라떼', 6000, '제주 말차로 만든 라떼'),
        ('BAR', '모둠 꼬치', 18000, '닭·대파·염통 꼬치 모둠'),
        ('BAR', '감자튀김', 9000, '트러플 소금을 뿌린 감자튀김'),
        ('BAR', '생맥주 500cc', 5000, '시원한 생맥주'),
        ('BAR', '하이볼', 8000, '레몬·자몽 중 선택'),
        ('BAR', '나가사키 짬뽕탕', 19000, '해산물이 들어간 국물 안주'),
        ('BAR', '치즈 플래터', 22000, '와인과 어울리는 치즈 모둠'),
        ('BAR', '하우스 와인 (글라스)', 9000, '레드·화이트 중 선택'),
        ('BAR', '골뱅이 소면', 17000, '새콤달콤한 골뱅이 무침'),
        ('ETC', '오늘의 정식', 11000, '매일 바뀌는 메인과 반찬'),
        ('ETC', '치킨 샐러드', 12000, '그릴드 치킨과 채소'),
        ('ETC', '수제 버거', 13000, '소고기 패티와 체다 치즈'),
        ('ETC', '쌀국수', 11000, '양지 육수로 끓인 쌀국수'),
        ('ETC', '비프 타코 (2pcs)', 10000, '직접 구운 토르티야'),
        ('ETC', '버터 치킨 커리', 13000, '난과 함께 나오는 커리'),
        ('ETC', '떡볶이', 6000, '쌀떡으로 만든 매콤한 떡볶이'),
        ('ETC', '에그 베네딕트', 15000, '수란과 홀랜다이즈 소스')
    ) AS m(category, name, price, description) ON m.category = ms.category
) x
WHERE rn <= menu_count;

-- 8. 방문 완료 예약
--   날짜를 먼저 뽑고, 같은 매장·같은 날 안에서 순번(p)을 매겨 (테이블, 시간대) 칸 하나씩에 배정한다.
--   칸은 테이블 수 × 시간대 수만큼 있고 순번마다 다른 칸이라 같은 테이블 시간이 겹치지 않는다.
--   하루 칸보다 많이 뽑힌 날의 초과분은 버리므로 여유분(25% + 3건)을 더 뽑아 둔다.
CREATE TEMP TABLE mock_reservation ON COMMIT DROP AS
WITH today AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS d
), candidate AS (
    SELECT ms.store_id, ms.owner_id, ms.category, ms.duration, ms.table_count, ms.slot_count, ms.target, g.k,
           ms.closed_dow,
           -- 절반은 최근 180일, 절반은 최근 730일에서 뽑아 최근일수록 많다. 어제가 가장 최근이다
           (SELECT d FROM today)
               - (1 + floor(CASE WHEN random() < 0.5 THEN random() * 180 ELSE random() * 730 END))::int AS d0
    FROM mock_store ms
    CROSS JOIN LATERAL generate_series(1, ceil(ms.target * 1.25)::int + 3) AS g(k)
    WHERE ms.target > 0
), shifted AS (
    -- 휴무 요일이면 하루 앞당긴다 (휴무일은 하나뿐이라 앞날은 영업일이다)
    SELECT c.*,
           CASE WHEN extract(isodow FROM d0)::int = closed_dow THEN d0 - 1 ELSE d0 END AS d
    FROM candidate c
), positioned AS (
    SELECT s.*, row_number() OVER (PARTITION BY store_id, d ORDER BY k) AS p
    FROM shifted s
), kept AS (
    SELECT p.*, row_number() OVER (PARTITION BY store_id ORDER BY random()) AS pick
    FROM positioned p
    WHERE p <= table_count * slot_count
), placed AS (
    SELECT k.store_id, k.owner_id, k.category, k.duration, k.d,
           -- 날마다 시작 테이블·시간대를 다르게 돌려서 한 테이블·한 시간대에만 몰리지 않게 한다
           pg_temp.mock_mod((k.p - 1) % k.table_count + pg_temp.mock_mod(hashtext(k.store_id || ':' || k.d), k.table_count), k.table_count) AS table_idx,
           pg_temp.mock_mod((k.p - 1) / k.table_count + pg_temp.mock_mod(hashtext(k.d || ':' || k.store_id), k.slot_count), k.slot_count) AS slot_idx
    FROM kept k
    WHERE k.pick <= k.target
)
SELECT pl.store_id, pl.owner_id, t.table_id, t.capacity, t.min_capacity, pl.duration,
       (pl.d + CASE pl.category
                   WHEN 'CAFE' THEN TIME '10:00' + make_interval(hours => pl.slot_idx)
                   WHEN 'BAR'  THEN TIME '17:00' + make_interval(hours => pl.slot_idx * 2)
                   ELSE (ARRAY[TIME '11:30', TIME '13:00', TIME '17:30', TIME '19:00', TIME '20:30'])[pl.slot_idx + 1]
               END) AT TIME ZONE 'Asia/Seoul' AS start_at
FROM placed pl
JOIN mock_table t ON t.store_id = pl.store_id AND t.idx = pl.table_idx;

INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at, reservation_end_at, party_size,
                         status, requested_at, confirmed_at, checked_in_at, checked_in_by_member_id, completed_at,
                         created_at, updated_at)
SELECT c.id, r.store_id, r.table_id, r.start_at, r.end_at, r.party_size,
       'COMPLETED', r.requested_at, r.requested_at,
       r.start_at + make_interval(mins => floor(random() * 10)::int), r.owner_id, r.end_at,
       r.requested_at, r.end_at
FROM (
    SELECT mr.*,
           mr.start_at + make_interval(mins => mr.duration) AS end_at,
           mr.min_capacity + floor(random() * (mr.capacity - mr.min_capacity + 1))::int AS party_size,
           mr.start_at - make_interval(hours => 6 + floor(random() * 330)::int) AS requested_at,
           -- 자주 예약하는 고객이 있도록 앞번호 고객을 더 자주 뽑는다
           least(:customer_count, 1 + floor(:customer_count * power(random(), 1.7))::int) AS cust_idx
    FROM mock_reservation mr
) r
JOIN mock_customer c ON c.idx = r.cust_idx
ORDER BY r.requested_at;

-- 9. 리뷰: 목 매장의 방문 완료 예약마다 1개. 별점은 매장 기본 별점 근처(정규분포)에서 뽑는다
INSERT INTO review (reservation_id, rating, content, created_at, updated_at)
SELECT reservation_id, rating,
       concat_ws(' ',
           CASE rating
               WHEN 5 THEN pg_temp.mock_pick(ARRAY['정말 맛있었어요!', '인생 맛집 찾았습니다.', '모든 메뉴가 만족스러웠어요.',
                                                  '기대 이상이었어요. 강력 추천합니다.', '재방문 의사 100%예요.',
                                                  '분위기도 맛도 최고였어요.', '여기는 꼭 다시 올 거예요.', '친구들 데려오고 싶은 곳이에요.'], r1)
               WHEN 4 THEN pg_temp.mock_pick(ARRAY['전반적으로 만족스러웠어요.', '맛있게 잘 먹었습니다.', '음식이 깔끔하고 맛있어요.',
                                                  '가격 대비 괜찮았어요.', '분위기가 좋아서 대화하기 편했어요.', '무난하게 맛있는 집이에요.'], r1)
               WHEN 3 THEN pg_temp.mock_pick(ARRAY['그냥 평범했어요.', '맛은 괜찮은데 특별하진 않았어요.', '기대보다는 조금 아쉬웠어요.',
                                                  '무난했지만 재방문은 고민돼요.', '양이 조금 적게 느껴졌어요.'], r1)
               WHEN 2 THEN pg_temp.mock_pick(ARRAY['기대에 못 미쳤어요.', '음식이 조금 식어서 나왔어요.', '가격에 비해 아쉬웠습니다.',
                                                  '응대가 조금 불친절했어요.'], r1)
               ELSE pg_temp.mock_pick(ARRAY['다시 가지는 않을 것 같아요.', '음식 나오는 데 너무 오래 걸렸어요.',
                                            '예약했는데도 한참 기다렸어요.', '위생 상태가 아쉬웠습니다.'], r1)
           END,
           CASE WHEN rating >= 4 AND r2 < 0.7 THEN
               CASE category
                   WHEN 'KOREAN'   THEN pg_temp.mock_pick(ARRAY['반찬이 정갈하게 나와요.', '찌개가 진하고 맛있어요.', '고기 질이 좋았어요.', '부모님 모시고 가기 좋아요.'], r3)
                   WHEN 'JAPANESE' THEN pg_temp.mock_pick(ARRAY['초밥 밥알 온도가 딱 좋았어요.', '생선이 신선했어요.', '튀김이 바삭했어요.', '셰프님 설명이 친절했어요.'], r3)
                   WHEN 'CHINESE'  THEN pg_temp.mock_pick(ARRAY['탕수육이 바삭했어요.', '짬뽕 국물이 진해요.', '단체로 가기 좋아요.', '요리 양이 넉넉해요.'], r3)
                   WHEN 'WESTERN'  THEN pg_temp.mock_pick(ARRAY['파스타 면 익힘이 좋았어요.', '스테이크 굽기가 완벽했어요.', '데이트 코스로 추천해요.', '와인 추천도 잘해 주셨어요.'], r3)
                   WHEN 'CAFE'     THEN pg_temp.mock_pick(ARRAY['커피 향이 좋아요.', '디저트가 너무 맛있어요.', '작업하기 좋은 분위기예요.', '창가 자리가 예뻐요.'], r3)
                   WHEN 'BAR'      THEN pg_temp.mock_pick(ARRAY['안주가 다 맛있어요.', '술 종류가 다양해요.', '분위기가 편안해요.', '2차로 오기 딱 좋아요.'], r3)
                   ELSE pg_temp.mock_pick(ARRAY['양이 넉넉해요.', '혼밥하기 편해요.', '메뉴가 다양해요.', '가볍게 먹기 좋아요.'], r3)
               END
           END,
           CASE WHEN r4 < 0.4 THEN
               CASE WHEN rating >= 4 THEN pg_temp.mock_pick(ARRAY['다음에 또 올게요.', '직원분들이 친절했어요.', '예약하고 가니 편했어요.',
                                                                 '주차는 조금 불편해요.', '웨이팅 없이 바로 들어갔어요.'], r5)
                    WHEN rating = 3 THEN '그래도 접근성은 좋아요.'
                    ELSE '개선되면 좋겠어요.'
               END
           END
       ),
       created_at, created_at
FROM (
    SELECT res.id AS reservation_id, ms.category,
           greatest(1, least(5, round(ms.base_rating
               + 0.7 * sqrt(-2 * ln(1 - random())) * cos(2 * pi() * random()))))::int AS rating,
           res.reservation_end_at + make_interval(mins => 30 + floor(random() * 4000)::int) AS created_at,
           random() AS r1, random() AS r2, random() AS r3, random() AS r4, random() AS r5
    FROM reservation res
    JOIN mock_store ms ON ms.store_id = res.store_id
) x
ORDER BY created_at;

-- 결과 요약
SELECT (SELECT count(*) FROM mock_store) AS stores,
       (SELECT count(*) FROM mock_customer) AS customers,
       (SELECT count(*) FROM mock_table) AS store_tables,
       (SELECT count(*) FROM mock_reservation) AS reservations,
       (SELECT sum(target) FROM mock_store) AS review_target;

COMMIT;

VACUUM ANALYZE member;
VACUUM ANALYZE store;
VACUUM ANALYZE store_table;
VACUUM ANALYZE business_hour;
VACUUM ANALYZE menu;
VACUUM ANALYZE reservation;
VACUUM ANALYZE review;

-- 확인용: 매장 리뷰 수 분포와 평균 별점
SELECT CASE WHEN cnt = 0 THEN '0'
            WHEN cnt <= 30 THEN '1~30'
            WHEN cnt <= 200 THEN '31~200'
            WHEN cnt <= 1000 THEN '201~1000'
            ELSE '1000+' END AS review_count_range,
       count(*) AS stores,
       round(avg(avg_rating), 2) AS avg_rating
FROM (
    SELECT s.id, count(r.id) AS cnt, avg(r.rating) AS avg_rating
    FROM store s
    JOIN member m ON m.id = s.owner_id AND m.email LIKE 'mock-owner-%@local.test'
    LEFT JOIN reservation res ON res.store_id = s.id
    LEFT JOIN review r ON r.reservation_id = res.id
    GROUP BY s.id
) t
GROUP BY 1
ORDER BY min(cnt);
