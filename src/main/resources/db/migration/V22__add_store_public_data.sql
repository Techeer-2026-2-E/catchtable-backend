-- 공공데이터 매장 적재 + 매장 좌표 (기능 #1 주변 매장)
--   - 주변 매장 조회에서 내 위치와의 거리를 계산하는 데 쓴다
--   - V10에서 지도(#4)로 미뤘던 좌표 컬럼을 #1을 위해 먼저 추가한다
--   - 기존 매장은 좌표가 없으므로 NULL 허용, 좌표 없는 매장은 주변 매장에서 제외된다
--   - 위도·경도는 항상 함께 있거나 함께 없어야 한다
--   - V14~V17은 웨이팅 호출·응답·착석·자동 만료(#90, #91, #107, #109)에서, V18·V19는 dev의 웨이팅 이력·결제에서, V20·V21은 예약금 스냅샷(#145)·환불(#153)에서 사용 중이라 V22를 쓴다
ALTER TABLE store
    ADD COLUMN latitude  DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION;

ALTER TABLE store
    ADD CONSTRAINT chk_store_location_pair
        CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT chk_store_latitude_range
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT chk_store_longitude_range
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180);

-- 주변 매장 조회의 사각형 범위 조건(latitude/longitude BETWEEN)용
CREATE INDEX idx_store_location ON store (latitude, longitude);

-- 공공데이터 매장 식별자 (서울시 일반음식점 인허가 관리번호, 예: 3020000-101-2001-07985)
--   - 적재를 다시 돌려도 중복되지 않도록 upsert 키로 쓴다
--   - 최대 22자 확인 (2026-10-28 데이터 기준)
--   - 점주가 직접 등록한 매장은 NULL (PostgreSQL UNIQUE는 NULL끼리 중복으로 보지 않는다)
ALTER TABLE store
    ADD COLUMN external_id VARCHAR(30),
    ADD CONSTRAINT uk_store_external_id UNIQUE (external_id);

-- 미입점 매장(공공데이터로만 들어온 매장)은 점주가 없다
--   - owner_id가 NULL이면 검색·지도 노출만 하고 예약·웨이팅 대상이 아니다
ALTER TABLE store
    ALTER COLUMN owner_id DROP NOT NULL;
