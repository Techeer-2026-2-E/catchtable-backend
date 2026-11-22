-- 실험 사이에 측정 매장(9000)의 웨이팅 상태만 비운다. 회원·매장은 남긴다.
--   docker compose exec -T postgres psql -U catchtable -d catchtable_loadtest -f - < scripts/notification-loadtest/reset.sql
\set ON_ERROR_STOP on
BEGIN;

DELETE FROM waiting_defer_history WHERE waiting_id IN (SELECT id FROM waiting WHERE store_id = 9000);
DELETE FROM waiting_end_history   WHERE waiting_id IN (SELECT id FROM waiting WHERE store_id = 9000);
DELETE FROM alimtalk_outbox       WHERE waiting_id IN (SELECT id FROM waiting WHERE store_id = 9000);
DELETE FROM waiting WHERE store_id = 9000;

COMMIT;

SELECT count(*) AS remaining_waitings FROM waiting WHERE store_id = 9000;
