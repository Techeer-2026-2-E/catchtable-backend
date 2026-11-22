package com.catchtable.waiting.repository;

import com.catchtable.waiting.dto.WaitingBatchItem;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import com.catchtable.waiting.entity.WaitingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WaitingBatchRepository {
    private final JdbcTemplate jdbc;

    public record Day(long lastSequence, int room, int pendingCount) {}
    public record NewReceipt(WaitingBatchItem.Key key, WaitingReceiptResponse receipt) {}

    // Bound every lock wait in this transaction. Writers are shared by all stores in this app.
    public void boundLockWait(Duration timeout) {
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, timeout.toMillis() + "ms");
    }

    public Set<Long> lockMembers(List<Long> memberIds) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT id FROM member WHERE id = ANY(?) ORDER BY id FOR NO KEY UPDATE
                """, Long.class, array("bigint", memberIds)));
    }

    public Map<WaitingBatchItem.Key, WaitingReceiptResponse> byKeys(Collection<WaitingBatchItem.Key> keys) {
        var result = new HashMap<WaitingBatchItem.Key, WaitingReceiptResponse>();
        jdbc.query("""
                SELECT k.request_key, r.* FROM unnest(?::bigint[], ?::text[]) AS input(member_id,request_key)
                JOIN waiting_receipt_key k USING (member_id,request_key)
                JOIN waiting_receipt r USING (member_id,receipt_id)
                """, rs -> {
            var key = new WaitingBatchItem.Key(rs.getLong("member_id"), rs.getString("request_key"));
            result.put(key, WaitingReceiptRepository.RECEIPT.mapRow(rs, 0));
        }, array("bigint", keys.stream().map(WaitingBatchItem.Key::memberId).toList()),
                array("text", keys.stream().map(WaitingBatchItem.Key::token).toList()));
        return result;
    }

    public Map<Long, WaitingReceiptResponse> pending(long storeId, LocalDate day, List<Long> members) {
        var result = new HashMap<Long, WaitingReceiptResponse>();
        jdbc.query("""
                SELECT * FROM waiting_receipt WHERE store_id=? AND received_date=?
                    AND member_id = ANY(?) AND member_active
                """, rs -> {
            result.put(rs.getLong("member_id"), WaitingReceiptRepository.RECEIPT.mapRow(rs, 0));
        }, storeId, day, array("bigint", members));
        return result;
    }

    public Set<Long> registered(long storeId, LocalDate day, List<Long> members) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT member_id FROM waiting WHERE store_id=? AND waiting_date=?
                    AND member_id = ANY(?) AND status = ANY(?)
                """, Long.class, storeId, day, array("bigint", members),
                array("varchar", WaitingStatus.ACTIVE.stream().map(Enum::name).toList())));
    }

    public Optional<Day> lockDay(long storeId, LocalDate day) {
        return jdbc.query("""
                SELECT last_sequence,capacity-pending_count AS room,pending_count FROM waiting_intake_day
                WHERE store_id=? AND received_date=? AND ledger_route FOR NO KEY UPDATE
                """, (rs, i) -> new Day(rs.getLong("last_sequence"), rs.getInt("room"),rs.getInt("pending_count")), storeId, day)
                .stream().findFirst();
    }

    public record Allocation(long previousSequence, int previousPending) {}

    public Optional<Allocation> issueRegular(long store, OffsetDateTime at, List<WaitingBatchItem> items, List<UUID> ids) {
        return append("SELECT previous_sequence,previous_pending FROM append_regular_open_receipts(?,?,?,?,?,?)",
                List.of(store,at),items,ids);
    }

    public Optional<Allocation> issueFresh(long store, LocalDate day, OffsetDateTime at,
                                           List<WaitingBatchItem> items, List<UUID> ids) {
        return append("SELECT previous_sequence,previous_pending FROM append_fresh_waiting_receipts(?,?,?,?,?,?,?)",
                List.of(store,day,at),items,ids);
    }

    private Optional<Allocation> append(String sql,List<?> scope,List<WaitingBatchItem> items,List<UUID> ids) {
        var args=new java.util.ArrayList<Object>(scope);
        args.add(array("bigint",items.stream().map(i->i.key().memberId()).toList()));
        args.add(array("uuid",ids));
        args.add(array("int4",items.stream().map(WaitingBatchItem::partyCount).toList()));
        args.add(array("text",items.stream().map(i->i.key().token()).toList()));
        return jdbc.query(sql,(rs,i)->new Allocation(rs.getLong(1),rs.getInt(2)),args.toArray()).stream().findFirst();
    }

    public void issue(long storeId, LocalDate day, long previousSequence, OffsetDateTime acceptedAt,
                      List<NewReceipt> receipts) {
        int count = receipts.size();
        int written = jdbc.update("""
                WITH incoming AS (
                    SELECT * FROM unnest(?::bigint[], ?::uuid[], ?::bigint[], ?::int[], ?::text[])
                        AS i(member_id,receipt_id,intake_sequence,party_count,request_key)
                ), advanced AS (
                    UPDATE waiting_intake_day SET pending_count=pending_count+?,last_sequence=last_sequence+?
                    WHERE store_id=? AND received_date=? AND last_sequence=? AND capacity-pending_count>=?
                    RETURNING store_id,received_date
                ), issued AS (
                    INSERT INTO waiting_receipt(member_id,receipt_id,store_id,received_date,intake_sequence,
                                                party_count,accepted_at)
                    SELECT i.member_id,i.receipt_id,a.store_id,a.received_date,i.intake_sequence,i.party_count,?
                    FROM incoming i CROSS JOIN advanced a RETURNING member_id,receipt_id
                )
                INSERT INTO waiting_receipt_key(member_id,request_key,receipt_id)
                SELECT i.member_id,i.request_key,i.receipt_id FROM incoming i JOIN issued USING(member_id,receipt_id)
                """, array("bigint", receipts.stream().map(r -> r.key().memberId()).toList()),
                array("uuid", receipts.stream().map(r -> r.receipt().receiptId()).toList()),
                array("bigint", receipts.stream().map(r -> r.receipt().intakeSequence()).toList()),
                array("integer", receipts.stream().map(r -> r.receipt().partyCount()).toList()),
                array("text", receipts.stream().map(r -> r.key().token()).toList()),
                count, count, storeId, day, previousSequence, count, acceptedAt);
        if (written != count) {
            throw new IllegalStateException("Locked intake day changed during receipt allocation");
        }
    }

    public void bindAliases(Map<WaitingBatchItem.Key, UUID> aliases) {
        var entries = List.copyOf(aliases.entrySet());
        jdbc.update("""
                INSERT INTO waiting_receipt_key(member_id,request_key,receipt_id)
                SELECT * FROM unnest(?::bigint[],?::text[],?::uuid[])
                """, array("bigint", entries.stream().map(e -> e.getKey().memberId()).toList()),
                array("text", entries.stream().map(e -> e.getKey().token()).toList()),
                array("uuid", entries.stream().map(Map.Entry::getValue).toList()));
    }

    private static SqlArrayValue array(String type, Collection<?> values) {
        return new SqlArrayValue(type, values.toArray());
    }
}
