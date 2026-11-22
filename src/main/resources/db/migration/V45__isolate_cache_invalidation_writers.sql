-- Writers on two overlapping dates must not lock each other's outbox rows in reverse order.
-- Coalesce repeated writes within the same business transaction only.
ALTER TABLE availability_cache_invalidation ADD COLUMN writer_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE availability_cache_invalidation DROP CONSTRAINT availability_cache_invalidation_pkey,
    ADD PRIMARY KEY(store_id,scope,writer_xid);
CREATE UNIQUE INDEX uk_availability_revision ON availability_cache_invalidation(revision);
CREATE OR REPLACE FUNCTION dirty_availability(s bigint,k text) RETURNS void LANGUAGE sql AS $$
    INSERT INTO availability_cache_invalidation(store_id,scope,revision,changed_at)
    VALUES(s,k,gen_random_uuid(),clock_timestamp()) ON CONFLICT(store_id,scope,writer_xid) DO NOTHING;
$$;
