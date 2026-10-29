-- 매장 이름 부분 일치(ILIKE '%키워드%') 검색용 trigram 인덱스
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_store_name_trgm ON store USING gin (name gin_trgm_ops);
