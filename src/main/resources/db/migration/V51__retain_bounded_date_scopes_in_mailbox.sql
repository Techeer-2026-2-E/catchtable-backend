ALTER TABLE availability_cache_invalidation ADD COLUMN affected_scopes text[];

-- Older drainers understand the scalar wildcard. New readers can still invalidate
-- the precise dates of a move until this mailbox accumulates more than 32 dates.
CREATE OR REPLACE FUNCTION dirty_availability(s bigint, k text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE xid xid8 := pg_current_xact_id();
BEGIN
    WITH candidate AS (
        SELECT ctid,coalesce(affected_scopes,ARRAY[scope]) AS old_scopes
        FROM availability_cache_invalidation WHERE store_id=s AND pooled
        ORDER BY (last_writer=xid) DESC NULLS LAST, changed_at LIMIT 1 FOR UPDATE SKIP LOCKED
    ), merged AS (
        SELECT ctid,CASE WHEN k='*' OR '*'=ANY(old_scopes) THEN ARRAY['*']
                         WHEN k=ANY(old_scopes) THEN old_scopes
                         WHEN cardinality(old_scopes)>=32 THEN ARRAY['*']
                         ELSE array_append(old_scopes,k) END AS scopes FROM candidate
    )
    UPDATE availability_cache_invalidation q SET affected_scopes=m.scopes,
        scope=CASE WHEN cardinality(m.scopes)=1 THEN m.scopes[1] ELSE '*' END,
        revision=gen_random_uuid(),changed_at=clock_timestamp(),visible_since=NULL,last_writer=xid
    FROM merged m WHERE q.ctid=m.ctid;
    IF NOT FOUND THEN
        INSERT INTO availability_cache_invalidation(store_id,scope,revision,changed_at,writer_xid,pooled,last_writer,affected_scopes)
        VALUES(s,k,gen_random_uuid(),clock_timestamp(),xid,true,xid,ARRAY[k]);
    END IF;
END;
$$;

CREATE INDEX ix_waiting_active_position ON waiting(store_id,waiting_date,queue_order) INCLUDE(id)
    WHERE status IN ('WAITING','CALLED','CONFIRMED');
