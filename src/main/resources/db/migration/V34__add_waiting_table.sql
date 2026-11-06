-- 착석 테이블 (착석 시에만 채움, 같은 매장 테이블만 허용)
ALTER TABLE waiting
    ADD COLUMN table_id BIGINT,
    ADD CONSTRAINT fk_waiting_table_store FOREIGN KEY (table_id, store_id)
        REFERENCES store_table (id, store_id);
