ALTER TABLE waiting_intake_day ADD COLUMN ledger_route boolean NOT NULL DEFAULT true;

ALTER TABLE waiting_receipt DROP CONSTRAINT waiting_receipt_status_check,
    ADD COLUMN waiting_id bigint REFERENCES waiting(id),
    ADD COLUMN claim_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD CONSTRAINT waiting_receipt_state CHECK (
        (status='PENDING' AND waiting_id IS NULL AND claim_token IS NULL AND lease_until IS NULL) OR
        (status='PROCESSING' AND waiting_id IS NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (status='WITHDRAWN' AND waiting_id IS NULL AND claim_token IS NULL AND lease_until IS NULL) OR
        (status IN ('REGISTERED','CANCELED') AND waiting_id IS NOT NULL AND claim_token IS NULL AND lease_until IS NULL)
    );
CREATE UNIQUE INDEX uk_waiting_receipt_registration ON waiting_receipt(waiting_id) WHERE waiting_id IS NOT NULL;
DROP INDEX uk_waiting_receipt_pending_member;
CREATE UNIQUE INDEX uk_waiting_receipt_unfinished_member ON waiting_receipt(member_id,store_id,received_date)
    WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX ix_waiting_receipt_head ON waiting_receipt(store_id,received_date,intake_sequence)
    WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX ix_waiting_day_dispatch ON waiting_intake_day(store_id,received_date) WHERE pending_count>0;

CREATE TABLE waiting_receipt_cancel (
    member_id bigint NOT NULL, receipt_id uuid NOT NULL, requested_at timestamptz NOT NULL,
    outcome text CHECK (outcome IN ('WITHDRAWN','CANCELED','REJECTED')), resolved_at timestamptz,
    PRIMARY KEY(member_id,receipt_id),
    FOREIGN KEY(member_id,receipt_id) REFERENCES waiting_receipt(member_id,receipt_id),
    CHECK ((resolved_at IS NULL)=(outcome IS NULL))
);
CREATE INDEX ix_waiting_cancel_unresolved ON waiting_receipt_cancel(member_id,receipt_id) WHERE outcome IS NULL;

-- Locks on these rows are held until the actual registration transaction commits or rolls back.
CREATE TABLE waiting_registration_permit (id integer PRIMARY KEY CHECK(id>0));
INSERT INTO waiting_registration_permit VALUES (1),(2);

-- Old binaries cannot register directly on a ledger day. The worker sets a transaction-local receipt
-- identity; both the restaurant waiting and its matching receipt are verified at commit.
CREATE FUNCTION guard_waiting_registration_route() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt uuid;
BEGIN
    PERFORM id FROM store WHERE id=NEW.store_id FOR SHARE;
    IF NOT EXISTS (SELECT FROM waiting_intake_day WHERE store_id=NEW.store_id AND received_date=NEW.waiting_date AND ledger_route) THEN
        RETURN NEW;
    END IF;
    receipt := NULLIF(current_setting('catchtable.processing_receipt',true),'')::uuid;
    IF NOT EXISTS(SELECT FROM waiting_receipt WHERE member_id=NEW.member_id AND receipt_id=receipt
        AND store_id=NEW.store_id AND received_date=NEW.waiting_date AND waiting_id=NEW.id) THEN
        RAISE EXCEPTION 'Missing durable receipt binding on a ledger day' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER guard_waiting_registration_route AFTER INSERT ON waiting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_waiting_registration_route();

-- Roll back only when all accepted receipts and cancellation intents have been reconciled. Keep the ledger
-- and keys readable. Concurrent intake/registration and cancellation requests take the same store lock.
CREATE FUNCTION retire_waiting_intake(s bigint, d date) RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM store WHERE id=s FOR UPDATE;
    PERFORM 1 FROM waiting_intake_day WHERE store_id=s AND received_date=d FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF EXISTS (SELECT FROM waiting_intake_day WHERE store_id=s AND received_date=d AND pending_count<>0)
        OR EXISTS (SELECT FROM waiting_receipt r LEFT JOIN waiting w ON w.id=r.waiting_id
            LEFT JOIN waiting_receipt_cancel c ON c.member_id=r.member_id AND c.receipt_id=r.receipt_id
            WHERE r.store_id=s AND r.received_date=d AND (r.status IN ('PENDING','PROCESSING')
                OR (c.receipt_id IS NOT NULL AND c.outcome IS NULL)
                OR (r.waiting_id IS NOT NULL AND (w.id IS NULL OR w.member_id<>r.member_id
                    OR w.store_id<>s OR w.waiting_date<>d)))) THEN RETURN false; END IF;
    UPDATE waiting_intake_day SET ledger_route=false WHERE store_id=s AND received_date=d;
    RETURN true;
END; $$;
