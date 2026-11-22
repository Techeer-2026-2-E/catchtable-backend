-- A dated policy change carries forward. Existing V41 daily rows remain explicit exceptions.
-- A NULL capacity schedules the legacy route. The day of an accepted receipt never changes.
CREATE TABLE waiting_intake_policy (
    store_id bigint NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    effective_date date NOT NULL,
    capacity integer CHECK (capacity > 0),
    PRIMARY KEY (store_id, effective_date)
);

CREATE FUNCTION waiting_policy_capacity(s bigint, d date) RETURNS integer LANGUAGE sql STABLE AS $$
    SELECT capacity FROM waiting_intake_policy
    WHERE store_id=s AND effective_date<=d ORDER BY effective_date DESC LIMIT 1;
$$;

CREATE FUNCTION waiting_ledger_route(s bigint, d date) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT coalesce((SELECT ledger_route FROM waiting_intake_day WHERE store_id=s AND received_date=d),
                    waiting_policy_capacity(s,d) IS NOT NULL);
$$;

-- Hot days use the existing shared-store path. Only a cold day needs the exclusive store lock.
CREATE FUNCTION open_waiting_intake_day(s bigint, d date) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE route boolean; cap integer;
BEGIN
    SELECT ledger_route INTO route FROM waiting_intake_day WHERE store_id=s AND received_date=d;
    IF FOUND THEN RETURN route; END IF;
    IF waiting_policy_capacity(s,d) IS NULL THEN RETURN false; END IF;
    PERFORM id FROM store WHERE id=s FOR UPDATE;
    SELECT ledger_route INTO route FROM waiting_intake_day WHERE store_id=s AND received_date=d;
    IF FOUND THEN RETURN route; END IF;
    cap := waiting_policy_capacity(s,d);
    IF cap IS NULL THEN RETURN false; END IF;
    INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES(s,d,cap);
    RETURN true;
END;
$$;

CREATE FUNCTION enable_waiting_intake(s bigint, d date, cap integer) RETURNS text LANGUAGE plpgsql AS $$
DECLARE snapshot waiting_intake_day%ROWTYPE; planned integer; outcome text := 'ENABLED';
BEGIN
    IF d IS NULL OR cap IS NULL OR cap<1 OR d<(clock_timestamp() AT TIME ZONE 'Asia/Seoul')::date
        THEN RETURN 'BLOCKED'; END IF;
    PERFORM id FROM store WHERE id=s FOR UPDATE;
    IF NOT FOUND THEN RETURN 'BLOCKED'; END IF;
    SELECT capacity INTO planned FROM waiting_intake_policy WHERE store_id=s AND effective_date=d;
    IF FOUND AND planned IS DISTINCT FROM cap THEN RETURN 'BLOCKED'; END IF;
    SELECT * INTO snapshot FROM waiting_intake_day WHERE store_id=s AND received_date=d FOR UPDATE;
    IF FOUND THEN
        IF NOT snapshot.ledger_route OR snapshot.capacity<>cap THEN RETURN 'BLOCKED'; END IF;
        outcome := 'ALREADY_ENABLED';
    ELSE
        IF EXISTS(SELECT FROM waiting WHERE store_id=s AND waiting_date=d) THEN RETURN 'BLOCKED'; END IF;
        INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES(s,d,cap);
    END IF;
    INSERT INTO waiting_intake_policy(store_id,effective_date,capacity) VALUES(s,d,cap)
    ON CONFLICT (store_id,effective_date) DO NOTHING;
    RETURN outcome;
END;
$$;

CREATE OR REPLACE FUNCTION retire_waiting_intake(s bigint,d date) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE persistent boolean; first_day date;
BEGIN
    PERFORM id FROM store WHERE id=s FOR UPDATE;
    SELECT EXISTS(SELECT FROM waiting_intake_policy WHERE store_id=s AND effective_date<=d) INTO persistent;
    IF NOT persistent AND NOT EXISTS(SELECT FROM waiting_intake_day WHERE store_id=s AND received_date=d)
        THEN RETURN false; END IF;
    first_day := CASE WHEN persistent THEN '-infinity'::date ELSE d END;
    -- Cancellation takes a conflicting lock on its day before storing the intent.
    PERFORM 1 FROM waiting_intake_day WHERE store_id=s AND received_date BETWEEN first_day AND d
        ORDER BY received_date FOR UPDATE;
    IF EXISTS(SELECT FROM waiting_intake_day WHERE store_id=s AND received_date BETWEEN first_day AND d AND pending_count<>0)
       OR EXISTS(SELECT FROM waiting_receipt r LEFT JOIN waiting w ON w.id=r.waiting_id
            LEFT JOIN waiting_receipt_cancel c ON c.member_id=r.member_id AND c.receipt_id=r.receipt_id
            WHERE r.store_id=s AND r.received_date BETWEEN first_day AND d AND (
                 r.status IN ('PENDING','PROCESSING') OR (c.receipt_id IS NOT NULL AND c.outcome IS NULL)
                 OR (r.waiting_id IS NOT NULL AND (w.id IS NULL OR w.member_id<>r.member_id
                        OR w.store_id<>s OR w.waiting_date<>r.received_date))
                 OR (c.outcome='CANCELED' AND w.status IS DISTINCT FROM 'CANCELED')
                 OR (c.outcome='WITHDRAWN' AND r.status<>'WITHDRAWN')))
       OR EXISTS(SELECT FROM (SELECT w.wait_number,
                lag(w.wait_number) OVER(PARTITION BY r.received_date ORDER BY r.intake_sequence) AS prev
                FROM waiting_receipt r JOIN waiting w ON w.id=r.waiting_id
                WHERE r.store_id=s AND r.received_date BETWEEN first_day AND d) numbered
                WHERE prev>=wait_number) THEN RETURN false; END IF;
    UPDATE waiting_intake_day SET ledger_route=false WHERE store_id=s AND received_date=d;
    IF persistent THEN
        INSERT INTO waiting_intake_policy(store_id,effective_date,capacity) VALUES(s,d,NULL)
        ON CONFLICT(store_id,effective_date) DO UPDATE SET capacity=NULL;
    END IF;
    RETURN true;
END;
$$;

-- Fence an older application even before an upgraded application opens the first day row.
CREATE OR REPLACE FUNCTION guard_waiting_registration_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM store WHERE id=NEW.store_id FOR SHARE;
    IF waiting_ledger_route(NEW.store_id,NEW.waiting_date)
       AND NOT EXISTS(SELECT FROM waiting_receipt WHERE waiting_id=NEW.id AND member_id=NEW.member_id
            AND store_id=NEW.store_id AND received_date=NEW.waiting_date) THEN
        RAISE EXCEPTION 'Missing durable receipt binding on a ledger day' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION waiting_slots_required(d date) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT FROM waiting_intake_day WHERE pending_count>0 OR (ledger_route AND received_date=d))
        OR EXISTS(SELECT FROM (
            SELECT DISTINCT ON (store_id) store_id,capacity FROM waiting_intake_policy
            WHERE effective_date<=d ORDER BY store_id,effective_date DESC
        ) p WHERE p.capacity IS NOT NULL AND NOT EXISTS(
            SELECT FROM waiting_intake_day WHERE store_id=p.store_id AND received_date=d AND NOT ledger_route));
$$;

CREATE OR REPLACE FUNCTION guard_waiting_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF waiting_slots_required(NEW.waiting_date) AND NOT take_waiting_registration_slot(true) THEN
        RAISE EXCEPTION 'No registration permits configured';
    END IF;
    RETURN NEW;
END;
$$;
