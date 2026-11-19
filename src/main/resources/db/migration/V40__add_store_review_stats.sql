-- 지도 별점순 마커용. 평균은 저장하지 않고 조회할 때 rating_sum / review_count로 계산한다.
-- 리뷰 작성·수정·삭제가 같은 트랜잭션에서 원자적 UPDATE로 두 값을 갱신한다.

-- 이후 쓰이는 페이지에 10% 여유를 둬 별점 갱신 UPDATE가 HOT update가 되게 한다.
-- 기존 페이지에는 소급되지 않는다. review_count·rating_sum에는 인덱스를 걸지 않는다(걸면 HOT 불가).
ALTER TABLE store SET (fillfactor = 90);

ALTER TABLE store
    ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rating_sum   BIGINT  NOT NULL DEFAULT 0;

-- 삭제되지 않은 리뷰로 채운다. review에는 store_id가 없어서 reservation을 거친다.
UPDATE store s
SET review_count = agg.review_count,
    rating_sum   = agg.rating_sum
FROM (
    SELECT res.store_id, count(*) AS review_count, sum(r.rating) AS rating_sum
    FROM review r
    JOIN reservation res ON res.id = r.reservation_id
    WHERE r.deleted_at IS NULL
    GROUP BY res.store_id
) agg
WHERE s.id = agg.store_id;
