-- Keep the same member slot across the handoff from receipt to restaurant number.
-- A different key must not enter between the end of PENDING and visibility of WAITING.
ALTER TABLE waiting_receipt ADD COLUMN member_active boolean NOT NULL DEFAULT false;
UPDATE waiting_receipt r SET member_active =
    r.status IN ('PENDING','PROCESSING') OR
    (r.status='REGISTERED' AND EXISTS(SELECT FROM waiting w WHERE w.id=r.waiting_id
                                      AND w.status IN ('WAITING','CALLED','CONFIRMED')));
CREATE UNIQUE INDEX uk_waiting_receipt_active_member_day
    ON waiting_receipt(member_id,store_id,received_date) WHERE member_active;

CREATE FUNCTION set_receipt_member_active() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.member_active := CASE WHEN NEW.status IN ('PENDING','PROCESSING') THEN true
        WHEN NEW.status='REGISTERED' THEN EXISTS(SELECT FROM waiting w WHERE w.id=NEW.waiting_id
                                                 AND w.status IN ('WAITING','CALLED','CONFIRMED'))
        ELSE false END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER waiting_receipt_member_activity
    BEFORE INSERT OR UPDATE OF status,waiting_id ON waiting_receipt
    FOR EACH ROW EXECUTE FUNCTION set_receipt_member_active();

CREATE FUNCTION reflect_restaurant_member_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE waiting_receipt SET member_active=(NEW.status IN ('WAITING','CALLED','CONFIRMED'))
    WHERE waiting_id=NEW.id AND status='REGISTERED';
    RETURN NULL;
END;
$$;
CREATE TRIGGER restaurant_member_activity AFTER UPDATE OF status ON waiting
    FOR EACH ROW WHEN ((OLD.status IN ('WAITING','CALLED','CONFIRMED')) <>
                       (NEW.status IN ('WAITING','CALLED','CONFIRMED')))
    EXECUTE FUNCTION reflect_restaurant_member_activity();

-- The caller already holds the member locks and a store lock and has validated opening hours.
-- This insert uses only PK/unique lookups, including FK checks against a newly growing ledger.
-- Restrict planner tuning to this function. Each immutable key still has its real foreign key.
CREATE FUNCTION append_fresh_waiting_receipts(s bigint,d date,at_time timestamptz,
    members bigint[],ids uuid[],parties integer[],keys text[])
    RETURNS TABLE(previous_sequence bigint,previous_pending integer)
    LANGUAGE plpgsql SET enable_seqscan=off AS $$
DECLARE n integer:=cardinality(members); collision text;
BEGIN
    IF n IS NULL OR n<1 OR n>1024 OR cardinality(ids) IS DISTINCT FROM n
       OR cardinality(parties) IS DISTINCT FROM n OR cardinality(keys) IS DISTINCT FROM n THEN
        RAISE EXCEPTION 'Invalid admission batch';
    END IF;
    RETURN QUERY
      WITH incoming AS MATERIALIZED (
          SELECT * FROM unnest(members,ids,parties,keys) WITH ORDINALITY
          AS i(member_id,receipt_id,party_count,request_key,ordinal)
      ), advanced AS (
          UPDATE waiting_intake_day q SET last_sequence=q.last_sequence+n,pending_count=q.pending_count+n
          WHERE q.store_id=s AND q.received_date=d AND q.ledger_route AND q.capacity-q.pending_count>=n
          RETURNING q.last_sequence-n AS seq,q.pending_count-n AS backlog
      ), issued AS (
          INSERT INTO waiting_receipt(member_id,receipt_id,store_id,received_date,intake_sequence,party_count,accepted_at)
          SELECT i.member_id,i.receipt_id,s,d,a.seq+i.ordinal,i.party_count,at_time FROM incoming i CROSS JOIN advanced a
          RETURNING 1
      ), bound AS (
          INSERT INTO waiting_receipt_key(member_id,request_key,receipt_id)
          SELECT i.member_id,i.request_key,i.receipt_id FROM incoming i WHERE (SELECT count(*) FROM issued)=n
          RETURNING 1
      )
      SELECT a.seq,a.backlog FROM advanced a WHERE (SELECT count(*) FROM bound)=n;
EXCEPTION WHEN unique_violation THEN
    GET STACKED DIAGNOSTICS collision=CONSTRAINT_NAME;
    IF collision NOT IN ('waiting_receipt_key_pkey','uk_waiting_receipt_active_member_day','uk_waiting_receipt_unfinished_member') THEN
        RAISE;
    END IF;
    -- No counter, key or receipt survives this subtransaction. The caller resolves
    -- replays, aliases, conflicts and partial capacity with the normal classifier.
    RETURN;
END;
$$;
