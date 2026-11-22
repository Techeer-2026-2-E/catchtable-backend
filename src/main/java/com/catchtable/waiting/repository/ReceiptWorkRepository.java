package com.catchtable.waiting.repository;

import com.catchtable.waiting.dto.WaitingLane;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReceiptWorkRepository {
    public record Work(long member,UUID receipt,WaitingLane lane,int party,String state,UUID token,boolean expired) {}
    public record Intent(long member,UUID receipt,long waitingId) {}
    private final JdbcTemplate db;

    public boolean lockLane(WaitingLane lane) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,81842))",
                Boolean.class,lane.key()));
    }

    public boolean permit() {
        return Boolean.TRUE.equals(db.queryForObject("SELECT take_waiting_registration_slot(false)",Boolean.class));
    }

    public Optional<Work> head(WaitingLane lane) {
        return db.query("""
                SELECT *,lease_until<=clock_timestamp() AS expired FROM waiting_receipt
                WHERE store_id=? AND received_date=? AND status IN ('PENDING','PROCESSING')
                ORDER BY intake_sequence LIMIT 1 FOR NO KEY UPDATE
                """,(r,i)->new Work(r.getLong("member_id"),r.getObject("receipt_id",UUID.class),lane,
                r.getInt("party_count"),r.getString("status"),r.getObject("claim_token",UUID.class),r.getBoolean("expired")),
                lane.storeId(),lane.day()).stream().findFirst();
    }

    public void claim(Work w,UUID token,long leaseMs) {
        db.update("""
                UPDATE waiting_receipt SET status='PROCESSING',claim_token=?,
                    lease_until=clock_timestamp()+?*interval '1 millisecond' WHERE member_id=? AND receipt_id=?
                """,token,leaseMs,w.member(),w.receipt());
    }

    public void release(Work w,UUID token) {
        db.update("""
                UPDATE waiting_receipt SET status='PENDING',claim_token=NULL,lease_until=NULL
                WHERE member_id=? AND receipt_id=? AND claim_token=?
                """,w.member(),w.receipt(),token);
    }

    public List<WaitingLane> lanes() {
        return db.query("""
                SELECT store_id,received_date FROM waiting_intake_day WHERE pending_count>0
                UNION SELECT r.store_id,r.received_date FROM waiting_receipt_cancel c
                    JOIN waiting_receipt r USING(member_id,receipt_id) WHERE c.outcome IS NULL
                ORDER BY 1,2
                """,(r,i)->new WaitingLane(r.getLong(1),r.getObject(2,LocalDate.class)));
    }

    public boolean hasWork(WaitingLane lane) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT FROM waiting_intake_day WHERE store_id=? AND received_date=? AND pending_count>0)
                    OR EXISTS(SELECT FROM waiting_receipt_cancel c JOIN waiting_receipt r USING(member_id,receipt_id)
                        WHERE c.outcome IS NULL AND r.store_id=? AND r.received_date=?)
                """,Boolean.class,lane.storeId(),lane.day(),lane.storeId(),lane.day()));
    }

    public boolean wantsCancel(long member,UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT FROM waiting_receipt_cancel WHERE member_id=? AND receipt_id=? AND outcome IS NULL)
                """,Boolean.class,member,id));
    }

    public void bindRegistration(Work w,UUID token,long waitingId) {
        int count=db.update("""
                UPDATE waiting_receipt SET status='REGISTERED',waiting_id=?,claim_token=NULL,lease_until=NULL
                WHERE member_id=? AND receipt_id=? AND claim_token=? AND status='PROCESSING'
                """,waitingId,w.member(),w.receipt(),token);
        if(count!=1) throw new IllegalStateException("Registration lease changed");
        free(w.lane());
    }

    public void requestCancel(long member,UUID id,WaitingLane lane,OffsetDateTime at) {
        db.queryForList("SELECT store_id FROM waiting_intake_day WHERE store_id=? AND received_date=? FOR KEY SHARE",
                Long.class,lane.storeId(),lane.day());
        db.update("""
                INSERT INTO waiting_receipt_cancel(member_id,receipt_id,requested_at) VALUES(?,?,?)
                ON CONFLICT DO NOTHING
                """,member,id,at);
    }

    public boolean withdraw(long member,UUID id,WaitingLane lane,UUID token) {
        int updated=db.update("""
                UPDATE waiting_receipt SET status='WITHDRAWN',claim_token=NULL,lease_until=NULL WHERE member_id=? AND receipt_id=?
                AND ((status='PENDING' AND ?::uuid IS NULL) OR (status='PROCESSING' AND claim_token=?))
                """,member,id,token,token);
        if(updated==0) return false;
        free(lane);
        resolve(member,id,"WITHDRAWN");
        return true;
    }

    private void free(WaitingLane lane) {
        if(db.update("UPDATE waiting_intake_day SET pending_count=pending_count-1 WHERE store_id=? AND received_date=? AND pending_count>0",
                lane.storeId(),lane.day())!=1) throw new IllegalStateException("Receipt capacity mismatch");
    }

    public List<WaitingLane> cancellationLanes() {
        return db.query("""
                SELECT DISTINCT r.store_id,r.received_date FROM waiting_receipt_cancel c
                JOIN waiting_receipt r USING(member_id,receipt_id) WHERE c.outcome IS NULL
                """,(r,i)->new WaitingLane(r.getLong(1),r.getObject(2,LocalDate.class)));
    }

    public Optional<Work> abandonedCancellation(WaitingLane lane) {
        return db.query("""
                SELECT r.* FROM waiting_receipt_cancel c JOIN waiting_receipt r USING(member_id,receipt_id)
                WHERE c.outcome IS NULL AND r.store_id=? AND r.received_date=?
                    AND (r.status='PENDING' OR (r.status='PROCESSING' AND r.lease_until<=clock_timestamp()))
                ORDER BY r.intake_sequence LIMIT 1 FOR NO KEY UPDATE OF r SKIP LOCKED
                """,(r,i)->new Work(r.getLong("member_id"),r.getObject("receipt_id",UUID.class),lane,
                r.getInt("party_count"),r.getString("status"),r.getObject("claim_token",UUID.class),true),
                lane.storeId(),lane.day()).stream().findFirst();
    }

    public Optional<Intent> nextRegisteredCancel(WaitingLane lane) {
        return db.query("""
                SELECT c.member_id,c.receipt_id,r.waiting_id FROM waiting_receipt_cancel c
                JOIN waiting_receipt r USING(member_id,receipt_id)
                WHERE c.outcome IS NULL AND r.waiting_id IS NOT NULL AND r.store_id=? AND r.received_date=?
                ORDER BY c.requested_at LIMIT 1 FOR NO KEY UPDATE OF c SKIP LOCKED
                """,(r,i)->new Intent(r.getLong(1),r.getObject(2,UUID.class),r.getLong(3)),lane.storeId(),lane.day())
                .stream().findFirst();
    }

    public void resolve(long member,UUID id,String outcome) {
        db.update("""
                UPDATE waiting_receipt_cancel SET outcome=?,resolved_at=clock_timestamp()
                WHERE member_id=? AND receipt_id=? AND outcome IS NULL
                """,outcome,member,id);
        if("CANCELED".equals(outcome)) db.update("UPDATE waiting_receipt SET status='CANCELED' WHERE member_id=? AND receipt_id=?",
                member,id);
    }

    public Optional<WaitingReceiptResponse> view(long member,UUID id) {
        return db.query("""
                SELECT r.*,w.wait_number,w.status AS waiting_status,c.outcome,
                    greatest(0,r.intake_sequence-(d.last_sequence-d.pending_count)-1) AS queued_ahead,
                    CASE WHEN c.receipt_id IS NOT NULL AND c.outcome IS NULL THEN 'CANCEL_REQUESTED'
                         WHEN w.status='CANCELED' THEN 'CANCELED' ELSE r.status END AS visible_status,
                    CASE WHEN w.status IN ('WAITING','CALLED','CONFIRMED') THEN
                      (SELECT count(*) FROM waiting a WHERE a.store_id=w.store_id AND a.waiting_date=w.waiting_date
                        AND a.status IN ('WAITING','CALLED','CONFIRMED') AND a.queue_order<w.queue_order) END AS teams_ahead
                FROM waiting_receipt r JOIN waiting_intake_day d USING(store_id,received_date) LEFT JOIN waiting w ON w.id=r.waiting_id
                    LEFT JOIN waiting_receipt_cancel c ON c.receipt_id=r.receipt_id AND c.member_id=r.member_id
                WHERE r.member_id=? AND r.receipt_id=?
                """,(r,i)->new WaitingReceiptResponse(id,r.getLong("store_id"),r.getObject("received_date",LocalDate.class),
                r.getLong("intake_sequence"),r.getInt("party_count"),r.getString("visible_status"),
                r.getObject("waiting_id",Long.class),r.getObject("wait_number",Integer.class),r.getObject("teams_ahead",Long.class),
                r.getString("waiting_status"),r.getString("outcome"),r.getLong("queued_ahead")),member,id).stream().findFirst();
    }
}
