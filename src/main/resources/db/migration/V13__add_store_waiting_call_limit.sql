ALTER TABLE store
    ADD COLUMN waiting_call_limit_minutes INT NOT NULL DEFAULT 10;

ALTER TABLE store
    ADD CONSTRAINT chk_store_waiting_call_limit CHECK (waiting_call_limit_minutes BETWEEN 1 AND 60);
