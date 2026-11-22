-- 매장 인기 점수 (최근 30일 예약·웨이팅 입장, 10분마다 갱신). 행이 없는 매장은 0점
CREATE TABLE store_popularity (
    store_id          BIGINT      NOT NULL,
    score             INT         NOT NULL,
    reservation_count INT         NOT NULL,
    waiting_count     INT         NOT NULL,
    calculated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_store_popularity PRIMARY KEY (store_id),
    CONSTRAINT fk_store_popularity_store FOREIGN KEY (store_id) REFERENCES store (id) ON DELETE CASCADE
);

CREATE INDEX idx_store_popularity_score ON store_popularity (score DESC, store_id);
