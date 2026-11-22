-- No day is routed to the ledger until explicitly configured.
CREATE TABLE waiting_intake_day (
    store_id bigint NOT NULL REFERENCES store(id),
    received_date date NOT NULL,
    capacity integer NOT NULL CHECK (capacity > 0),
    pending_count integer NOT NULL DEFAULT 0 CHECK (pending_count BETWEEN 0 AND capacity),
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= pending_count),
    PRIMARY KEY (store_id, received_date)
);

CREATE TABLE waiting_receipt (
    member_id bigint NOT NULL REFERENCES member(id),
    receipt_id uuid NOT NULL,
    store_id bigint NOT NULL,
    received_date date NOT NULL,
    intake_sequence bigint NOT NULL CHECK (intake_sequence > 0),
    party_count integer NOT NULL CHECK (party_count BETWEEN 1 AND 20),
    accepted_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status = 'PENDING'),
    PRIMARY KEY (member_id, receipt_id),
    UNIQUE (store_id, received_date, intake_sequence),
    FOREIGN KEY (store_id, received_date) REFERENCES waiting_intake_day(store_id, received_date)
);
CREATE UNIQUE INDEX uk_waiting_receipt_pending_member
    ON waiting_receipt(member_id, store_id, received_date) WHERE status = 'PENDING';

CREATE TABLE waiting_receipt_key (
    member_id bigint NOT NULL,
    request_key varchar(128) NOT NULL,
    receipt_id uuid NOT NULL,
    PRIMARY KEY (member_id, request_key),
    FOREIGN KEY (member_id, receipt_id) REFERENCES waiting_receipt(member_id, receipt_id)
);
