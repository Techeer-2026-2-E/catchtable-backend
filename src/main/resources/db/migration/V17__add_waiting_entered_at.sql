-- 웨이팅 입장 시각
ALTER TABLE waiting
    ADD COLUMN entered_at TIMESTAMP WITH TIME ZONE;
