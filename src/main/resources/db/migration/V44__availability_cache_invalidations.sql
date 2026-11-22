-- Coalesce invalidations by affected scope. The row and business change share one transaction.
CREATE TABLE availability_cache_control(id integer PRIMARY KEY CHECK(id=1), tracking boolean NOT NULL);
INSERT INTO availability_cache_control VALUES(1,false);
CREATE TABLE availability_cache_invalidation(
    store_id bigint NOT NULL, scope text NOT NULL, revision uuid NOT NULL, changed_at timestamptz NOT NULL,
    PRIMARY KEY(store_id,scope)
);
CREATE INDEX ix_availability_cache_drain ON availability_cache_invalidation(changed_at);

CREATE FUNCTION dirty_availability(s bigint, k text) RETURNS void LANGUAGE sql AS $$
    INSERT INTO availability_cache_invalidation VALUES(s,k,gen_random_uuid(),clock_timestamp())
    ON CONFLICT(store_id,scope) DO UPDATE SET revision=excluded.revision,changed_at=excluded.changed_at;
$$;

CREATE FUNCTION dirty_reservation_interval(s bigint, a timestamptz, b timestamptz) RETURNS void LANGUAGE plpgsql AS $$
DECLARE first_day date; last_day date; duration_minutes int;
BEGIN
    SELECT reservation_duration_minutes INTO duration_minutes FROM store WHERE id=s;
    IF duration_minutes IS NULL THEN RETURN; END IF;
    first_day := ((a AT TIME ZONE 'Asia/Seoul') - make_interval(mins=>duration_minutes))::date;
    last_day := ((b-interval '1 microsecond') AT TIME ZONE 'Asia/Seoul')::date;
    IF last_day-first_day>64 THEN PERFORM dirty_availability(s,'*'); RETURN; END IF;
    PERFORM dirty_availability(s,to_char(first_day+i,'YYYY-MM-DD')) FROM generate_series(0,last_day-first_day) i;
END; $$;

CREATE FUNCTION capture_reservation_availability() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT (SELECT tracking FROM availability_cache_control WHERE id=1) THEN RETURN NULL; END IF;
    IF TG_OP<>'INSERT' AND OLD.status NOT IN ('CANCELLED','EXPIRED') THEN
        PERFORM dirty_reservation_interval(OLD.store_id,OLD.reservation_start_at,OLD.reservation_end_at);
    END IF;
    IF TG_OP<>'DELETE' AND NEW.status NOT IN ('CANCELLED','EXPIRED') THEN
        PERFORM dirty_reservation_interval(NEW.store_id,NEW.reservation_start_at,NEW.reservation_end_at);
    END IF;
    RETURN NULL;
END; $$;
CREATE TRIGGER reservation_availability_dirty AFTER INSERT OR DELETE OR UPDATE OF store_id,table_id,
    reservation_start_at,reservation_end_at,status,hold_expires_at,approval_payment_id,approval_quarantined
    ON reservation FOR EACH ROW EXECUTE FUNCTION capture_reservation_availability();

CREATE FUNCTION capture_store_availability() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid bigint;
BEGIN
    IF NOT (SELECT tracking FROM availability_cache_control WHERE id=1) THEN RETURN NULL; END IF;
    IF TG_TABLE_NAME='store' THEN PERFORM dirty_availability(NEW.id,'*');
    ELSE
        IF TG_OP<>'INSERT' THEN PERFORM dirty_availability(OLD.store_id,'*'); END IF;
        IF TG_OP='INSERT' OR (TG_OP='UPDATE' AND NEW.store_id<>OLD.store_id) THEN
            sid:=NEW.store_id; PERFORM dirty_availability(sid,'*');
        END IF;
    END IF;
    RETURN NULL;
END; $$;
CREATE TRIGGER store_policy_availability_dirty AFTER UPDATE OF reservation_duration_minutes,reservation_slot_minutes,
    booking_open_days,booking_deadline_minutes,owner_id ON store FOR EACH ROW EXECUTE FUNCTION capture_store_availability();
CREATE TRIGGER table_availability_dirty AFTER INSERT OR DELETE OR UPDATE OF store_id,capacity,min_capacity,status
    ON store_table FOR EACH ROW EXECUTE FUNCTION capture_store_availability();
CREATE TRIGGER hours_availability_dirty AFTER INSERT OR UPDATE OR DELETE ON business_hour
    FOR EACH ROW EXECUTE FUNCTION capture_store_availability();
CREATE TRIGGER special_day_availability_dirty AFTER INSERT OR UPDATE OR DELETE ON special_business_day
    FOR EACH ROW EXECUTE FUNCTION capture_store_availability();

