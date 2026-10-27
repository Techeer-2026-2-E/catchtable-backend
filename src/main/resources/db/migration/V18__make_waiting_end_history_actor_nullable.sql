-- 시스템 자동 만료 이력은 처리자 없음
ALTER TABLE waiting_end_history ALTER COLUMN actor_id DROP NOT NULL;
