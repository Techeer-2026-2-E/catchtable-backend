package com.catchtable.waiting.repository;

import com.catchtable.waiting.dto.WaitingReceiptResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WaitingReceiptRepository {
    private final JdbcTemplate jdbc;
    static final RowMapper<WaitingReceiptResponse> RECEIPT = (rs, index) ->
            new WaitingReceiptResponse(rs.getObject("receipt_id", UUID.class), rs.getLong("store_id"),
                    rs.getObject("received_date", LocalDate.class), rs.getLong("intake_sequence"),
                    rs.getInt("party_count"), rs.getString("status"));

    public boolean enabled(long storeId, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM waiting_intake_day WHERE store_id=? AND received_date=? AND ledger_route)
                """, Boolean.class, storeId, date));
    }

    public enum DayRoute { LEGACY, CONFIGURED, OPEN }

    public DayRoute batchRoute(long storeId, LocalDate date) {
        String route = jdbc.queryForObject("""
                SELECT CASE WHEN d.ledger_route THEN 'OPEN'
                            WHEN d.ledger_route IS NULL AND waiting_policy_capacity(s.store_id,s.day) IS NOT NULL
                                THEN 'CONFIGURED' ELSE 'LEGACY' END
                FROM (VALUES (?::bigint,?::date)) s(store_id,day)
                LEFT JOIN waiting_intake_day d ON d.store_id=s.store_id AND d.received_date=s.day
                """, String.class, storeId, date);
        return DayRoute.valueOf(route);
    }

    public boolean openForLockedStore(long storeId, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT open_waiting_intake_day(?,?)",
                Boolean.class, storeId, date));
    }

    public void reserveLegacyRegistrationSlot(LocalDate day) {
        Boolean granted=jdbc.queryForObject("""
            SELECT CASE WHEN waiting_slots_required(?)
                THEN take_waiting_registration_slot(true) ELSE true END
            """,Boolean.class,day);
        if (!Boolean.TRUE.equals(granted)) throw new IllegalStateException("No registration slots configured");
    }

    // Serialize key reuse for this member, including two requests on opposite sides of midnight.
    // NO KEY UPDATE still allows unrelated foreign-key checks referencing the same member.
    public boolean lockMember(long memberId) {
        return !jdbc.queryForList("SELECT id FROM member WHERE id=? FOR NO KEY UPDATE",
                Long.class, memberId).isEmpty();
    }

    public Optional<WaitingReceiptResponse> byKey(long memberId, String key) {
        return jdbc.query("""
                SELECT r.* FROM waiting_receipt_key k
                JOIN waiting_receipt r USING (member_id, receipt_id)
                WHERE k.member_id=? AND k.request_key=?
                """, RECEIPT, memberId, key).stream().findFirst();
    }

    public Optional<WaitingReceiptResponse> pending(long memberId, long storeId, LocalDate date) {
        return jdbc.query("""
                SELECT * FROM waiting_receipt WHERE member_id=? AND store_id=? AND received_date=?
                    AND (status IN ('PENDING','PROCESSING') OR (status='REGISTERED' AND EXISTS (SELECT FROM waiting w WHERE w.id=waiting_receipt.waiting_id AND w.status IN ('WAITING','CALLED','CONFIRMED'))))
                """, RECEIPT, memberId, storeId, date).stream().findFirst();
    }

    public Optional<WaitingReceiptResponse> byOwner(long memberId, UUID id) {
        return jdbc.query("SELECT * FROM waiting_receipt WHERE member_id=? AND receipt_id=?",
                RECEIPT, memberId, id).stream().findFirst();
    }

    public record Issued(WaitingReceiptResponse receipt, boolean idleHead) {}

    public Optional<Issued> issue(long memberId, long storeId, LocalDate date,
                                                   int partyCount, OffsetDateTime receivedAt, String key) {
        // One store/day row establishes commit order and enforces capacity across all applications.
        return jdbc.query("""
                WITH issued AS (
                    UPDATE waiting_intake_day SET pending_count=pending_count+1, last_sequence=last_sequence+1
                    WHERE store_id=? AND received_date=? AND ledger_route AND pending_count<capacity
                    RETURNING store_id, received_date, last_sequence, pending_count
                ), receipt AS (
                    INSERT INTO waiting_receipt(member_id, receipt_id, store_id, received_date, intake_sequence,
                                                party_count, accepted_at)
                    SELECT ?, ?, store_id, received_date, last_sequence, ?, ? FROM issued
                    RETURNING member_id, receipt_id, store_id, received_date, intake_sequence, party_count, status
                ), binding AS (
                    INSERT INTO waiting_receipt_key(member_id, request_key, receipt_id)
                    SELECT member_id, ?, receipt_id FROM receipt
                )
                SELECT receipt.*, issued.pending_count AS backlog FROM receipt CROSS JOIN issued
                """, (rs,i)->new Issued(RECEIPT.mapRow(rs,i).withAhead(rs.getInt("backlog")-1),rs.getInt("backlog")==1), storeId, date, memberId, UUID.randomUUID(), partyCount, receivedAt, key)
                .stream().findFirst();
    }

    public void bind(long memberId, String key, UUID receiptId) {
        jdbc.update("INSERT INTO waiting_receipt_key(member_id, request_key, receipt_id) VALUES (?,?,?)",
                memberId, key, receiptId);
    }
}
