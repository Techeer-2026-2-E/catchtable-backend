ALTER TABLE waiting
    ADD COLUMN recall_count     INT NOT NULL DEFAULT 0,
    ADD COLUMN last_recalled_at TIMESTAMP WITH TIME ZONE;
