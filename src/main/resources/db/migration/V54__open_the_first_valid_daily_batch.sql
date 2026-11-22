-- Freeze an inherited day at its first eligible admission, including the opening second.
CREATE OR REPLACE FUNCTION append_regular_open_receipts(s bigint,hint timestamptz,
    members bigint[],ids uuid[],parties integer[],keys text[])
    RETURNS TABLE(previous_sequence bigint,previous_pending integer)
    LANGUAGE plpgsql AS $$
DECLARE n integer:=cardinality(members); seen integer; d date; checked timestamptz; local_time time; hinted_local time; existing boolean; cold boolean;
BEGIN
    IF n IS NULL OR n<2 OR n>1024 THEN RETURN; END IF;
    d:=(hint AT TIME ZONE 'Asia/Seoul')::date;
    -- Preserve member-before-store lock ordering, including reuse of one key at two stores.
    PERFORM m.id FROM member m WHERE m.id=ANY(members) ORDER BY m.id FOR NO KEY UPDATE;
    GET DIAGNOSTICS seen=ROW_COUNT;
    IF seen<>n THEN RETURN; END IF;
    SELECT day.ledger_route INTO existing FROM waiting_intake_day day WHERE day.store_id=s AND day.received_date=d;
    cold:=NOT FOUND;
    IF cold THEN
        IF waiting_policy_capacity(s,d) IS NULL THEN RETURN; END IF;
        PERFORM st.id FROM store st WHERE st.id=s AND st.owner_id IS NOT NULL AND st.waiting_available FOR UPDATE NOWAIT;
    ELSE
        IF NOT existing THEN RETURN; END IF;
        PERFORM st.id FROM store st WHERE st.id=s AND st.owner_id IS NOT NULL AND st.waiting_available FOR SHARE NOWAIT;
    END IF;
    IF NOT FOUND THEN RETURN; END IF;
    checked:=clock_timestamp();
    IF abs(extract(epoch FROM checked-hint))>0.25 OR (checked AT TIME ZONE 'Asia/Seoul')::date<>d THEN RETURN; END IF;
    local_time:=(checked AT TIME ZONE 'Asia/Seoul')::time;
    hinted_local:=(hint AT TIME ZONE 'Asia/Seoul')::time;
    IF NOT EXISTS(SELECT FROM business_hour h WHERE h.store_id=s AND h.day_of_week=extract(isodow FROM d)::int
        AND h.deleted_at IS NULL AND h.break_start_time IS NULL AND h.break_end_time IS NULL
        AND h.open_time<h.closing_time
        AND h.open_time<=local_time AND local_time<h.closing_time
        AND h.open_time<=hinted_local AND hinted_local<h.closing_time)
       OR EXISTS(SELECT FROM special_business_day special WHERE special.store_id=s AND special.deleted_at IS NULL
                 AND special.business_date IN (d-1,d)) THEN RETURN; END IF;
    IF cold AND NOT open_waiting_intake_day(s,d) THEN RETURN; END IF;
    RETURN QUERY SELECT a.previous_sequence,a.previous_pending
        FROM append_fresh_waiting_receipts(s,d,checked,members,ids,parties,keys) a;
    GET DIAGNOSTICS seen=ROW_COUNT;
    IF cold AND seen=0 THEN
        RAISE EXCEPTION 'No admission allocated the first daily snapshot' USING ERRCODE='P5401';
    END IF;
EXCEPTION WHEN lock_not_available OR SQLSTATE 'P5401' THEN RETURN;
END;
$$;
