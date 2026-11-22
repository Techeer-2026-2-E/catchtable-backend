-- Preserve existing rows for older drainers. A new mailbox covers either one date or
-- the entire store. Concurrent writers skip occupied mailboxes instead of waiting on
-- each other's business transactions. Accumulation depends on stores and concurrent
-- writers, not on the number of commits during a Redis outage.
ALTER TABLE availability_cache_invalidation ADD COLUMN pooled boolean NOT NULL DEFAULT false;
ALTER TABLE availability_cache_invalidation ADD COLUMN last_writer xid8;
CREATE UNIQUE INDEX uk_availability_mailbox ON availability_cache_invalidation(store_id,writer_xid) WHERE pooled;

CREATE OR REPLACE FUNCTION dirty_availability(s bigint, k text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE xid xid8 := pg_current_xact_id();
BEGIN
    WITH candidate AS (
        SELECT ctid FROM availability_cache_invalidation
        WHERE store_id=s AND pooled
        ORDER BY (last_writer=xid) DESC NULLS LAST, changed_at
        LIMIT 1 FOR UPDATE SKIP LOCKED
    )
    UPDATE availability_cache_invalidation q
        SET scope=CASE WHEN q.scope=k THEN k ELSE '*' END,
            revision=gen_random_uuid(), changed_at=clock_timestamp(), visible_since=NULL, last_writer=xid
        FROM candidate WHERE q.ctid=candidate.ctid;
    IF NOT FOUND THEN
        INSERT INTO availability_cache_invalidation(store_id,scope,revision,changed_at,writer_xid,pooled,last_writer)
        VALUES(s,k,gen_random_uuid(),clock_timestamp(),xid,true,xid);
    END IF;
END;
$$;
