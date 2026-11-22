-- A sufficient proof for the ordinary weekly window, not a second implementation
-- of the complete scheduling policy. Breaks, overnight hours, special days, a
-- cold route, a boundary or a replay all return to the normal domain classifier.
CREATE FUNCTION append_regular_open_receipts(s bigint,hint timestamptz,
    members bigint[],ids uuid[],parties integer[],keys text[])
    RETURNS TABLE(previous_sequence bigint,previous_pending integer)
    LANGUAGE plpgsql AS $$
DECLARE n integer:=cardinality(members); seen integer; d date; checked timestamptz; local_time time;
BEGIN
    IF n IS NULL OR n<2 OR n>1024 THEN RETURN; END IF;
    d:=(hint AT TIME ZONE 'Asia/Seoul')::date;
    -- Preserve member-before-store lock ordering, including reuse of one key at two stores.
    PERFORM m.id FROM member m WHERE m.id=ANY(members) ORDER BY m.id FOR NO KEY UPDATE;
    GET DIAGNOSTICS seen=ROW_COUNT;
    IF seen<>n THEN RETURN; END IF;
    PERFORM st.id FROM store st JOIN waiting_intake_day day ON day.store_id=st.id
        WHERE st.id=s AND st.owner_id IS NOT NULL AND st.waiting_available AND day.received_date=d AND day.ledger_route
        FOR SHARE OF st NOWAIT;
    IF NOT FOUND THEN RETURN; END IF;
    checked:=clock_timestamp();
    IF abs(extract(epoch FROM checked-hint))>0.25 OR (checked AT TIME ZONE 'Asia/Seoul')::date<>d THEN RETURN; END IF;
    local_time:=(checked AT TIME ZONE 'Asia/Seoul')::time;
    IF NOT EXISTS(SELECT FROM business_hour h WHERE h.store_id=s AND h.day_of_week=extract(isodow FROM d)::int
        AND h.deleted_at IS NULL AND h.break_start_time IS NULL AND h.break_end_time IS NULL
        AND h.open_time<h.closing_time AND local_time-h.open_time>interval '1 second'
        AND h.closing_time-local_time>interval '1 second')
       OR EXISTS(SELECT FROM special_business_day special WHERE special.store_id=s AND special.deleted_at IS NULL
                 AND special.business_date IN (d-1,d)) THEN RETURN; END IF;
    RETURN QUERY SELECT a.previous_sequence,a.previous_pending
        FROM append_fresh_waiting_receipts(s,d,checked,members,ids,parties,keys) a;
EXCEPTION WHEN lock_not_available THEN RETURN;
END;
$$;
