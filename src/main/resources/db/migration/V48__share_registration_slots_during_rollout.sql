-- The same transaction reuses its permit. Every participating app takes the store lock before the slot.
CREATE FUNCTION take_waiting_registration_slot(block_if_busy boolean) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE slot integer;
BEGIN
    IF nullif(current_setting('catchtable.registration_slot',true),'') IS NOT NULL THEN RETURN true; END IF;
    SELECT id INTO slot FROM waiting_registration_permit ORDER BY id LIMIT 1 FOR NO KEY UPDATE SKIP LOCKED;
    IF slot IS NULL AND block_if_busy THEN
        SELECT id INTO slot FROM waiting_registration_permit ORDER BY id LIMIT 1 FOR NO KEY UPDATE;
    END IF;
    IF slot IS NULL THEN RETURN false; END IF;
    PERFORM set_config('catchtable.registration_slot',slot::text,true);
    RETURN true;
END; $$;

CREATE FUNCTION guard_waiting_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT FROM waiting_intake_day WHERE (ledger_route AND received_date=NEW.waiting_date) OR pending_count>0)
       AND NOT take_waiting_registration_slot(true) THEN
        RAISE EXCEPTION 'No registration permits configured';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER waiting_slot_before_insert BEFORE INSERT ON waiting FOR EACH ROW EXECUTE FUNCTION guard_waiting_slot();

