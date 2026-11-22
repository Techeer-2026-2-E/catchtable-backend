-- A receipt binding is visible at deferred constraint time in the registration transaction.
CREATE OR REPLACE FUNCTION guard_waiting_registration_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM store WHERE id=NEW.store_id FOR SHARE;
    IF EXISTS(SELECT FROM waiting_intake_day WHERE store_id=NEW.store_id AND received_date=NEW.waiting_date AND ledger_route)
       AND NOT EXISTS(SELECT FROM waiting_receipt WHERE waiting_id=NEW.id AND member_id=NEW.member_id
            AND store_id=NEW.store_id AND received_date=NEW.waiting_date) THEN
        RAISE EXCEPTION 'Missing durable receipt binding on a ledger day' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION retire_waiting_intake(s bigint,d date) RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM store WHERE id=s FOR UPDATE;
    PERFORM 1 FROM waiting_intake_day WHERE store_id=s AND received_date=d FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF EXISTS(SELECT FROM waiting_intake_day WHERE store_id=s AND received_date=d AND pending_count<>0)
       OR EXISTS(SELECT FROM waiting_receipt r LEFT JOIN waiting w ON w.id=r.waiting_id
               LEFT JOIN waiting_receipt_cancel c ON c.member_id=r.member_id AND c.receipt_id=r.receipt_id
               WHERE r.store_id=s AND r.received_date=d AND (
                 r.status IN ('PENDING','PROCESSING') OR (c.receipt_id IS NOT NULL AND c.outcome IS NULL)
                 OR (r.waiting_id IS NOT NULL AND (w.id IS NULL OR w.member_id<>r.member_id OR w.store_id<>s OR w.waiting_date<>d))
                 OR (c.outcome='CANCELED' AND w.status IS DISTINCT FROM 'CANCELED')
                 OR (c.outcome='WITHDRAWN' AND r.status<>'WITHDRAWN')))
       OR EXISTS(SELECT FROM (SELECT w.wait_number,lag(w.wait_number) OVER(ORDER BY r.intake_sequence) AS prev
                FROM waiting_receipt r JOIN waiting w ON w.id=r.waiting_id WHERE r.store_id=s AND r.received_date=d) seq
                WHERE prev>=wait_number) THEN RETURN false; END IF;
    UPDATE waiting_intake_day SET ledger_route=false WHERE store_id=s AND received_date=d;
    RETURN true;
END; $$;
CREATE TRIGGER waiting_intake_no_reopen BEFORE UPDATE OF ledger_route ON waiting_intake_day
    FOR EACH ROW WHEN (NOT OLD.ledger_route AND NEW.ledger_route) EXECUTE FUNCTION guard_waiting_intake_day_activation();

