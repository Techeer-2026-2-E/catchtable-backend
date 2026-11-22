-- Serialize opening a ledger day with the legacy route's store lock. The first actual
-- registration fixes that day to the legacy route, including after it is cancelled.
CREATE FUNCTION guard_waiting_intake_day_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM store WHERE id = NEW.store_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM waiting WHERE store_id = NEW.store_id AND waiting_date = NEW.received_date) THEN
        RAISE EXCEPTION 'Waiting registration already started on this store day'
            USING ERRCODE = '23514', CONSTRAINT = 'waiting_intake_day_requires_unused_day';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER waiting_intake_day_activation
    BEFORE INSERT OR UPDATE OF store_id, received_date ON waiting_intake_day
    FOR EACH ROW EXECUTE FUNCTION guard_waiting_intake_day_activation();
