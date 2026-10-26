-- 웨이팅 호출 응답
--   - responded_at: 고객 방문 의사 응답 시각
--   - arrival_intent: 방문 의사 (COMING)
ALTER TABLE waiting
    ADD COLUMN responded_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN arrival_intent VARCHAR(20);
