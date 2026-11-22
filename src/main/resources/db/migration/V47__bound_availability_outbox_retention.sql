-- Age from the first observation of a COMMITTED row, not from a trigger running in an open TX.
ALTER TABLE availability_cache_invalidation ADD COLUMN visible_since timestamptz;
CREATE INDEX ix_availability_unobserved ON availability_cache_invalidation(changed_at) WHERE visible_since IS NULL;
CREATE INDEX ix_availability_observed ON availability_cache_invalidation(visible_since) WHERE visible_since IS NOT NULL;

CREATE FUNCTION enable_availability_tracking() RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT tracking FROM availability_cache_control WHERE id=1) THEN RETURN true; END IF;
    PERFORM id FROM availability_cache_control WHERE id=1 FOR UPDATE NOWAIT;
    LOCK TABLE store,store_table,business_hour,special_business_day,reservation IN SHARE MODE NOWAIT;
    UPDATE availability_cache_control SET tracking=true WHERE id=1;
    RETURN true;
EXCEPTION WHEN lock_not_available THEN RETURN false;
END; $$;
