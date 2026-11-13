-- 로컬 데모용 입점 매장 데이터 (운영·CI에는 넣지 않는다)
--
-- 공공데이터로 적재한 미입점 매장 중 강남역·성수·홍대입구 근처 일부를 골라
-- 데모 점주를 연결하고 테이블·영업시간·메뉴·방문 완료 예약·리뷰를 채운다.
-- 나머지 미입점 매장은 그대로 둔다 (검색·지도 노출만, 예약·웨이팅 없음).
--
-- 실행 순서: 앱 실행(Flyway) → scripts/local-seed.sql → 공공데이터 CSV 적재 → 이 스크립트
--   docker compose exec -T postgres psql -U catchtable -d catchtable < scripts/local-demo-seed.sql
--
-- - 데모 계정 이메일은 demo-*@local.test 이다. 이미 있으면 아무것도 하지 않는다.
-- - 데모 점주가 연결된 매장은 owner_id가 생기므로 CSV를 다시 적재해도 덮어쓰지 않는다.
-- - 메뉴·리뷰는 템플릿으로 만든 가짜 데이터다. 실제 매장 정보가 아니므로 화면 캡처를 외부에 공개하지 않는다.
-- - 매장 사진(image_url)은 비워 둔다. 프런트가 카테고리별 기본 이미지를 보여준다.

\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    st            RECORD;
    v_owner_id    BIGINT;
    v_customer_id BIGINT;
    v_table_id    BIGINT;
    v_visits      INT;
    v_day         DATE;
    v_time        TIME;
    v_start       TIMESTAMPTZ;
    v_end         TIMESTAMPTZ;
    v_party       INT;
    v_rating      INT;
    v_content     TEXT;
    v_res_id      BIGINT;
    v_store_count INT;
    v_review_count INT := 0;
    ratings       INT[]  := ARRAY[5, 4, 5, 3, 5, 4, 4, 5, 2, 5];
    good_reviews  TEXT[] := ARRAY[
        '음식이 정말 맛있고 직원분들도 친절했어요. 다음에 또 방문할게요!',
        '예약하고 가니 기다리지 않아서 좋았습니다. 분위기도 좋아요.',
        '친구들이랑 갔는데 다들 만족했어요. 양도 넉넉합니다.',
        '재방문 의사 100%! 메뉴 하나하나 정성이 느껴져요.',
        '데이트 코스로 추천합니다. 조용하고 깔끔해요.',
        '가격 대비 만족도가 높아요. 점심 식사로 딱이에요.',
        '매장이 깨끗하고 응대가 빨라서 좋았습니다.',
        '부모님 모시고 갔는데 좋아하셨어요. 주차 안내도 친절했어요.'
    ];
    mid_reviews   TEXT[] := ARRAY[
        '맛은 괜찮았는데 저녁 시간이라 조금 시끄러웠어요.',
        '무난하게 먹기 좋아요. 음식 나오는 데 시간이 조금 걸렸습니다.',
        '기대보다는 평범했지만 직원분들이 친절했어요.'
    ];
    bad_reviews   TEXT[] := ARRAY[
        '예약 시간에 갔는데 자리 안내가 늦었어요. 음식은 괜찮았습니다.'
    ];
BEGIN
    IF EXISTS (SELECT 1 FROM member WHERE email LIKE 'demo-%@local.test') THEN
        RAISE NOTICE '데모 데이터가 이미 있어 건너뜁니다. 다시 넣으려면 빈 로컬 DB에서 처음부터 실행하세요.';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM store WHERE external_id IS NOT NULL) THEN
        RAISE EXCEPTION '공공데이터 매장이 없습니다. CSV를 먼저 적재하세요 (README 참고).';
    END IF;

    -- 데모 고객 (리뷰 작성자)
    INSERT INTO member (email, password, name, phone, user_type)
    SELECT format('demo-customer-%s@local.test', lpad(n::text, 2, '0')),
           'mock-password', nm, format('010-1000-%s', lpad(n::text, 4, '0')), 'CUSTOMER'
    FROM unnest(ARRAY['김민준', '이서연', '박도윤', '최지우', '정하준', '강서아', '조은우', '윤지아'])
         WITH ORDINALITY AS t(nm, n);

    -- 지역별·카테고리별로 중심에서 가까운 매장을 고른다 (기타·중식은 1곳, 나머지는 2곳)
    CREATE TEMP TABLE demo_store ON COMMIT DROP AS
    WITH area(area_name, lat, lng) AS (
        VALUES ('강남역', 37.4979, 127.0276),
               ('성수', 37.5446, 127.0559),
               ('홍대입구', 37.5572, 126.9245)
    ), candidate AS (
        SELECT s.id, s.category, a.area_name,
               row_number() OVER (
                   PARTITION BY a.area_name, s.category
                   ORDER BY (s.latitude - a.lat) ^ 2 + ((s.longitude - a.lng) * 0.79) ^ 2, s.id
               ) AS rn
        FROM store s
        JOIN area a
          ON s.latitude BETWEEN a.lat - 0.006 AND a.lat + 0.006
         AND s.longitude BETWEEN a.lng - 0.0075 AND a.lng + 0.0075
        WHERE s.owner_id IS NULL AND s.external_id IS NOT NULL
    )
    SELECT id, category, area_name, row_number() OVER (ORDER BY id)::int AS seq
    FROM candidate
    WHERE rn <= CASE WHEN category IN ('CHINESE', 'ETC') THEN 1 ELSE 2 END;

    SELECT count(*) INTO v_store_count FROM demo_store;
    IF v_store_count = 0 THEN
        RAISE EXCEPTION '강남역·성수·홍대입구 근처에 미입점 공공데이터 매장이 없습니다. CSV 적재 범위를 확인하세요.';
    END IF;

    -- 매장마다 데모 점주 1명
    INSERT INTO member (email, password, name, phone, user_type)
    SELECT format('demo-owner-%s@local.test', id), 'mock-password',
           format('데모 점주 %s', seq), format('010-2000-%s', lpad(seq::text, 4, '0')), 'OWNER'
    FROM demo_store;

    UPDATE store s
    SET owner_id = m.id,
        waiting_available = (d.seq % 2 = 0),
        reservation_duration_minutes = CASE d.category WHEN 'CAFE' THEN 60 WHEN 'BAR' THEN 120 ELSE 90 END,
        reservation_slot_minutes = 30,
        arrival_grace_minutes = 10,
        description = CASE d.category
            WHEN 'KOREAN'   THEN d.area_name || ' 근처에서 정성껏 차린 한식을 선보입니다. 점심 특선과 저녁 단체석을 운영해요.'
            WHEN 'JAPANESE' THEN d.area_name || '의 일식 전문점. 매일 들여오는 재료로 초밥과 덮밥을 준비합니다.'
            WHEN 'CHINESE'  THEN d.area_name || '에서 오래 사랑받은 중식당. 코스 요리와 단체 모임 예약이 가능해요.'
            WHEN 'WESTERN'  THEN d.area_name || '의 양식 레스토랑. 파스타와 스테이크, 와인 페어링을 즐겨 보세요.'
            WHEN 'CAFE'     THEN d.area_name || ' 골목의 카페. 직접 로스팅한 원두와 수제 디저트를 준비합니다.'
            WHEN 'BAR'      THEN d.area_name || '의 술집. 퇴근 후 가볍게 한잔하기 좋은 안주와 주류가 있어요.'
            ELSE d.area_name || ' 근처 음식점. 편하게 식사하기 좋은 메뉴를 준비했습니다.'
        END,
        updated_at = now()
    FROM demo_store d
    JOIN member m ON m.email = format('demo-owner-%s@local.test', d.id)
    WHERE s.id = d.id;

    -- 테이블: 2인 2개, 4인 3개, 6인 1개
    INSERT INTO store_table (store_id, table_number, capacity, min_capacity, status)
    SELECT d.id, t.n, t.cap, t.min_cap, 'ACTIVE'
    FROM demo_store d
    CROSS JOIN (VALUES (1, 2, 1), (2, 2, 1), (3, 4, 2), (4, 4, 2), (5, 4, 2), (6, 6, 3)) AS t(n, cap, min_cap);

    -- 영업시간: 카테고리별 패턴, 3곳 중 1곳은 평일 하루 휴무 (1=월요일)
    INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time, break_start_time, break_end_time)
    SELECT d.id, dow, h.open_t::time, h.close_t::time, h.break_s::time, h.break_e::time
    FROM demo_store d
    JOIN (VALUES ('KOREAN',   '11:00', '22:00', '15:00', '17:00'),
                 ('JAPANESE', '11:30', '22:00', '15:00', '17:30'),
                 ('CHINESE',  '11:00', '21:30', '15:00', '16:30'),
                 ('WESTERN',  '11:30', '22:00', '15:00', '17:00'),
                 ('CAFE',     '10:00', '22:00', NULL,    NULL),
                 ('BAR',      '17:00', '02:00', NULL,    NULL),
                 ('ETC',      '11:00', '21:00', NULL,    NULL))
         AS h(category, open_t, close_t, break_s, break_e) ON h.category = d.category
    CROSS JOIN generate_series(1, 7) AS dow
    WHERE d.seq % 3 <> 0 OR dow <> (d.seq % 5) + 1;

    -- 메뉴: 카테고리 템플릿 + 매장마다 가격을 조금씩 다르게 (500원 단위)
    INSERT INTO menu (store_id, name, price, description)
    SELECT d.id, m.name,
           (round(m.price * (0.9 + (d.seq % 5) * 0.05) / 500) * 500)::int,
           m.description
    FROM demo_store d
    JOIN (VALUES
        ('KOREAN', '김치찌개', 9000, '묵은지와 돼지고기를 넣고 끓인 찌개'),
        ('KOREAN', '제육볶음', 10000, '매콤한 양념에 볶은 돼지고기'),
        ('KOREAN', '된장찌개', 9000, '집된장으로 끓인 구수한 찌개'),
        ('KOREAN', '불고기 정식', 13000, '간장 불고기와 반찬 정식'),
        ('KOREAN', '해물파전', 16000, '오징어·새우를 넣은 파전'),
        ('JAPANESE', '모둠 초밥 (12pcs)', 22000, '오늘의 생선으로 만든 초밥'),
        ('JAPANESE', '연어 덮밥', 15000, '숙성 연어와 간장 소스'),
        ('JAPANESE', '돈카츠 정식', 13000, '두툼한 등심 돈카츠'),
        ('JAPANESE', '냉우동', 10000, '쯔유에 담근 탱탱한 면'),
        ('JAPANESE', '가라아게', 9000, '일본식 닭튀김'),
        ('CHINESE', '짜장면', 8000, '춘장을 볶아 만든 기본 짜장'),
        ('CHINESE', '짬뽕', 10000, '해산물이 들어간 얼큰한 국물'),
        ('CHINESE', '탕수육 (중)', 22000, '바삭한 등심 튀김과 새콤한 소스'),
        ('CHINESE', '마파두부밥', 11000, '얼얼한 두부 덮밥'),
        ('CHINESE', '깐풍기', 26000, '매콤달콤한 닭튀김'),
        ('WESTERN', '트러플 크림 파스타', 19000, '트러플 오일을 더한 크림 파스타'),
        ('WESTERN', '토마토 해산물 파스타', 18000, '새우·홍합을 넣은 토마토 파스타'),
        ('WESTERN', '마르게리타 피자', 21000, '화덕에 구운 기본 피자'),
        ('WESTERN', '채끝 스테이크', 39000, '미디엄 레어 추천'),
        ('WESTERN', '리코타 샐러드', 14000, '수제 리코타 치즈와 제철 채소'),
        ('CAFE', '아메리카노', 4500, '직접 로스팅한 원두'),
        ('CAFE', '카페 라떼', 5500, '고소한 우유와 에스프레소'),
        ('CAFE', '바닐라 빈 라떼', 6000, '바닐라 빈 시럽을 직접 만들어요'),
        ('CAFE', '바스크 치즈케이크', 7000, '겉은 진하고 속은 부드러운 케이크'),
        ('CAFE', '소금빵', 3500, '매일 아침 굽는 소금빵'),
        ('BAR', '모둠 꼬치', 18000, '닭·대파·염통 꼬치 모둠'),
        ('BAR', '감자튀김', 9000, '트러플 소금을 뿌린 감자튀김'),
        ('BAR', '생맥주 500cc', 5000, '시원한 생맥주'),
        ('BAR', '하이볼', 8000, '레몬·자몽 중 선택'),
        ('BAR', '나가사키 짬뽕탕', 19000, '해산물이 들어간 국물 안주'),
        ('ETC', '오늘의 정식', 11000, '매일 바뀌는 메인과 반찬'),
        ('ETC', '치킨 샐러드', 12000, '그릴드 치킨과 채소'),
        ('ETC', '수제 버거', 13000, '소고기 패티와 체다 치즈'),
        ('ETC', '쌀국수', 11000, '양지 육수로 끓인 쌀국수')
    ) AS m(category, name, price, description) ON m.category = d.category;

    -- 방문 완료 예약 + 리뷰: 매장당 3~8건, 서로 다른 날짜라 테이블 시간이 겹치지 않는다
    FOR st IN
        SELECT ds.*, s.owner_id, s.reservation_duration_minutes AS duration
        FROM demo_store ds JOIN store s ON s.id = ds.id
        ORDER BY ds.seq
    LOOP
        v_owner_id := st.owner_id;
        v_visits := 3 + st.seq % 6;

        FOR k IN 1..v_visits LOOP
            v_day := current_date - (k * 6 + st.seq % 5 + 1);
            v_time := CASE st.category
                WHEN 'CAFE' THEN TIME '14:00'
                WHEN 'BAR'  THEN TIME '19:30'
                ELSE CASE WHEN k % 2 = 0 THEN TIME '12:00' ELSE TIME '18:30' END
            END;
            v_start := (v_day + v_time) AT TIME ZONE 'Asia/Seoul';
            v_end := v_start + make_interval(mins => st.duration);
            v_party := (ARRAY[2, 2, 4, 3, 2, 4])[1 + (st.seq + k) % 6];

            SELECT id INTO v_table_id
            FROM store_table
            WHERE store_id = st.id AND table_number = CASE WHEN v_party <= 2 THEN 1 ELSE 3 END;

            SELECT id INTO v_customer_id
            FROM member
            WHERE email = format('demo-customer-%s@local.test', lpad((1 + (st.seq + k) % 8)::text, 2, '0'));

            INSERT INTO reservation (
                member_id, store_id, table_id, reservation_start_at, reservation_end_at, party_size,
                status, requested_at, confirmed_at, checked_in_at, checked_in_by_member_id, completed_at
            ) VALUES (
                v_customer_id, st.id, v_table_id, v_start, v_end, v_party,
                'COMPLETED', v_start - INTERVAL '3 days', v_start - INTERVAL '3 days',
                v_start + INTERVAL '5 minutes', v_owner_id, v_end
            )
            RETURNING id INTO v_res_id;

            v_rating := ratings[1 + (st.seq * 3 + k) % array_length(ratings, 1)];
            v_content := CASE
                WHEN v_rating >= 4 THEN good_reviews[1 + (st.seq + k * 3) % array_length(good_reviews, 1)]
                WHEN v_rating = 3  THEN mid_reviews[1 + (st.seq + k) % array_length(mid_reviews, 1)]
                ELSE bad_reviews[1]
            END;

            INSERT INTO review (reservation_id, rating, content, created_at, updated_at)
            VALUES (v_res_id, v_rating, v_content, v_end + INTERVAL '2 hours', v_end + INTERVAL '2 hours');

            v_review_count := v_review_count + 1;
        END LOOP;
    END LOOP;

    RAISE NOTICE '데모 입점 매장 %곳, 리뷰 %건을 넣었습니다.', v_store_count, v_review_count;
END
$$;

-- 목 점주(회원 2)가 관리할 매장: 지역마다 데모 매장 1곳씩 (홍대입구 한식, 성수 카페, 강남역 일식)
--   - 점주 화면은 X-Member-Id=2 로 확인한다. 프런트 .env.local 에 VITE_OWNER_STORE_IDS 로 이 매장 id 를 넣는다
--   - 회원 2가 이미 공공데이터 매장을 갖고 있으면 건너뛴다 (다시 실행해도 매장이 늘지 않는다)
DO $$
DECLARE
    v_count INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM member WHERE id = 2 AND user_type = 'OWNER') THEN
        RAISE NOTICE '목 점주(회원 2)가 없어 점주 매장 연결을 건너뜁니다. scripts/local-seed.sql 을 먼저 실행하세요.';
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM store WHERE owner_id = 2 AND external_id IS NOT NULL) THEN
        RAISE NOTICE '목 점주에게 이미 매장이 연결되어 있어 건너뜁니다.';
        RETURN;
    END IF;

    UPDATE store
    SET owner_id = 2, updated_at = now()
    WHERE id IN (
        SELECT DISTINCT ON (p.area_name) s.id
        FROM (VALUES ('홍대입구', 37.5572, 126.9245, 'KOREAN'),
                     ('성수', 37.5446, 127.0559, 'CAFE'),
                     ('강남역', 37.4979, 127.0276, 'JAPANESE')) AS p(area_name, lat, lng, category)
        JOIN store s ON s.category = p.category
        JOIN member m ON m.id = s.owner_id AND m.email LIKE 'demo-owner-%@local.test'
        WHERE s.latitude BETWEEN p.lat - 0.006 AND p.lat + 0.006
          AND s.longitude BETWEEN p.lng - 0.0075 AND p.lng + 0.0075
        ORDER BY p.area_name, (s.latitude - p.lat) ^ 2 + ((s.longitude - p.lng) * 0.79) ^ 2
    );
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '목 점주(회원 2)에게 매장 %곳을 연결했습니다.', v_count;
END
$$;

-- 확인용: 데모 매장과 목 점주(owner_id=2) 매장. owner_id=2인 id를 프런트 VITE_OWNER_STORE_IDS에 넣는다
SELECT s.id, s.owner_id, s.name, s.category, s.waiting_available,
       (SELECT count(*) FROM menu WHERE store_id = s.id) AS menus,
       (SELECT count(*) FROM review r JOIN reservation res ON res.id = r.reservation_id
        WHERE res.store_id = s.id) AS reviews
FROM store s
LEFT JOIN member m ON m.id = s.owner_id
WHERE m.email LIKE 'demo-owner-%@local.test'
   OR (s.owner_id = 2 AND s.external_id IS NOT NULL)
ORDER BY s.owner_id, s.id;

COMMIT;
