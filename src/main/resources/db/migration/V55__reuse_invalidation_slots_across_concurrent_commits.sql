ALTER TABLE availability_cache_invalidation ADD COLUMN mailbox_slot integer;
CREATE UNIQUE INDEX uk_availability_store_slot ON availability_cache_invalidation(store_id,mailbox_slot)
    WHERE mailbox_slot IS NOT NULL;

-- A transaction owns exactly one numbered slot, across every store it changes.
-- ON CONFLICT follows the current logical row even when another transaction has
-- replaced its physical tuple since our statement snapshot.
CREATE OR REPLACE FUNCTION dirty_availability(s bigint,k text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE slot integer; xid xid8:=pg_current_xact_id();
BEGIN
    slot:=nullif(current_setting('catchtable.availability_slot',true),'')::integer;
    IF slot IS NULL THEN
        slot:=0;
        WHILE NOT pg_try_advisory_xact_lock(1747325901,slot) LOOP slot:=slot+1; END LOOP;
        PERFORM set_config('catchtable.availability_slot',slot::text,true);
    END IF;
    INSERT INTO availability_cache_invalidation AS q
        (store_id,scope,revision,changed_at,writer_xid,pooled,last_writer,affected_scopes,mailbox_slot)
    VALUES(s,k,gen_random_uuid(),clock_timestamp(),xid,true,xid,ARRAY[k],slot)
    ON CONFLICT (store_id,mailbox_slot) WHERE mailbox_slot IS NOT NULL
    DO UPDATE SET
        affected_scopes=CASE WHEN k='*' OR '*'=ANY(q.affected_scopes) THEN ARRAY['*']
            WHEN k=ANY(q.affected_scopes) THEN q.affected_scopes
            WHEN cardinality(q.affected_scopes)>=32 THEN ARRAY['*'] ELSE array_append(q.affected_scopes,k) END,
        scope=CASE WHEN q.scope=k THEN k ELSE '*' END,
        revision=excluded.revision,changed_at=excluded.changed_at,last_writer=xid,visible_since=NULL;
END;
$$;
