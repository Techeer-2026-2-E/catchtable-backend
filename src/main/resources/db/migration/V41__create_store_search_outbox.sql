-- 매장 검색 색인 변경 동기화 Outbox (#287)
--   - 매장·메뉴 수정과 같은 트랜잭션에 store_id만 기록, 워커가 처리 시점의 DB 값으로 색인
--   - 같은 매장이 여러 줄 쌓여도 된다 (워커가 매장 단위로 묶음)
--   - 매장이 없어져도 색인에서 지워야 하므로 store FK를 두지 않는다
CREATE TABLE store_search_outbox (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY,
    store_id        BIGINT       NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_store_search_outbox PRIMARY KEY (id),
    CONSTRAINT chk_store_search_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_store_search_outbox_due ON store_search_outbox (next_attempt_at, id);
