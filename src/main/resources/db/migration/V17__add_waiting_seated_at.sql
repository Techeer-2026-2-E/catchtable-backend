-- 웨이팅 착석 시각
ALTER TABLE waiting
    ADD COLUMN seated_at TIMESTAMP WITH TIME ZONE;
