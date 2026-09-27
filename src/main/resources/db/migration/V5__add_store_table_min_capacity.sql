-- 테이블 최소 인원 (기능 #28 보완, #33 허용 인원)
--   - 테이블별 허용 인원은 min_capacity ~ capacity. 최대 인원은 기존 capacity
--   - DEFAULT는 기존 row를 채우는 용도다. JPA는 모든 컬럼 값을 넣으므로 새 row 기본값은 엔티티에서 채운다
ALTER TABLE store_table
    ADD COLUMN min_capacity INT NOT NULL DEFAULT 1;

ALTER TABLE store_table
    ADD CONSTRAINT chk_store_table_capacity_range CHECK (min_capacity >= 1 AND min_capacity <= capacity);
