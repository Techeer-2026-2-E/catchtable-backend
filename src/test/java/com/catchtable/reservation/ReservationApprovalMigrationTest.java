package com.catchtable.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReservationApprovalMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void quarantinesOnlyLegacyPendingMoneyWithoutInventingOrChangingLedgerData() throws Exception {
        String schema = "approval_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("SET search_path TO " + schema + ", public");
                try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".sql"))
                            .filter(p -> version(p) < 24)
                            .sorted(Comparator.comparingInt(ReservationApprovalMigrationTest::version)).toList()) {
                        sql.execute(Files.readString(path));
                    }
                }
                sql.execute(Files.readAllLines(Path.of("scripts/local-seed.sql")).stream()
                        .filter(line -> !line.startsWith("\\")).collect(Collectors.joining("\n")));
                sql.execute("""
                        INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at,
                            reservation_end_at, party_size, status, requested_at, confirmed_at,
                            hold_expires_at, deposit_amount_snapshot, refund_policy_snapshot)
                        SELECT 1, 1, 1, '2030-01-02 18:00+09'::timestamptz + n * interval '1 day',
                            '2030-01-02 19:00+09'::timestamptz + n * interval '1 day', 2,
                            CASE n WHEN 8 THEN 'EXPIRED' WHEN 9 THEN 'CANCELLED'
                                WHEN 10 THEN 'CONFIRMED' ELSE 'PENDING_PAYMENT' END,
                            '2030-01-01 12:00+09', CASE WHEN n = 10 THEN '2030-01-01 12:01+09'::timestamptz END,
                            '2030-01-01 12:05+09', 10001, 'STANDARD'
                        FROM generate_series(1, 10) n;
                        INSERT INTO payment (member_id, reservation_id, order_id, order_name, amount,
                            payment_key, status, approved_at, refunded_amount)
                        SELECT 1, id, 'migration-order-' || id, 'deposit', 10001,
                            CASE WHEN id IN (3,4,5,9,10) THEN 'migration-key-' || id END,
                            CASE id WHEN 1 THEN 'READY' WHEN 2 THEN 'TIMEOUT' WHEN 3 THEN 'DONE'
                                WHEN 4 THEN 'PARTIAL_REFUNDED' WHEN 5 THEN 'REFUNDED'
                                WHEN 6 THEN 'FAILED' WHEN 7 THEN 'CANCELED' WHEN 8 THEN 'TIMEOUT'
                                ELSE 'DONE' END,
                            CASE WHEN id IN (3,4,5,9,10) THEN '2030-01-01 12:03+09'::timestamptz END,
                            CASE id WHEN 4 THEN 1000 WHEN 5 THEN 10001 ELSE 0 END
                        FROM reservation;
                        INSERT INTO payment_status_history (payment_id, previous_status, status, source, detail, created_at)
                        SELECT id, 'READY', status, 'TOSS_QUERY', 'legacy fixture', '2030-01-01 12:03+09'
                        FROM payment WHERE status <> 'READY';
                        INSERT INTO payment_refund (payment_id, idempotency_key, cancel_reason, refund_amount,
                            status, total_refunded_amount, requested_at, completed_at)
                        VALUES (4, 'migration-refund', 'fixture', 1000, 'COMPLETED', 1000,
                            '2030-01-01 12:03+09', '2030-01-01 12:04+09');
                        """);
                String beforeReservations;
                try (var rows = sql.executeQuery("SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM reservation r")) {
                    rows.next();
                    beforeReservations = rows.getString(1);
                }
                String ledgerQuery = """
                        SELECT jsonb_build_array(
                            (SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payment p),
                            (SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM payment_status_history h),
                            (SELECT jsonb_agg(to_jsonb(f) ORDER BY id) FROM payment_refund f))::text
                        """;
                String beforeLedger;
                try (var rows = sql.executeQuery(ledgerQuery)) {
                    rows.next();
                    beforeLedger = rows.getString(1);
                }
                sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V24__protect_reservation_payment_approval.sql")));
                try (var rows = sql.executeQuery("SELECT * FROM reservation ORDER BY id")) {
                    for (int id = 1; id <= 10; id++) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong("id")).isEqualTo(id);
                        assertThat(rows.getBoolean("approval_quarantined")).isEqualTo(id >= 2 && id <= 5);
                        for (String column : new String[]{"approval_started_at", "approval_payment_id", "approval_order_id", "approval_payment_key"}) {
                            assertThat(rows.getObject(column)).isNull();
                        }
                    }
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("""
                        SELECT jsonb_agg(to_jsonb(r) - ARRAY['approval_quarantined', 'approval_started_at',
                            'approval_payment_id', 'approval_order_id', 'approval_payment_key'] ORDER BY id)::text
                        FROM reservation r
                        """)) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo(beforeReservations);
                }
                try (var rows = sql.executeQuery(ledgerQuery)) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo(beforeLedger);
                }
                try (var rows = sql.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'reservation'::regclass AND conname = 'ex_reservation_table_period'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).contains("EXCLUDE USING gist", "CANCELLED", "EXPIRED");
                }
            } finally {
                sql.execute("ROLLBACK");
                sql.execute("RESET search_path");
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static int version(Path path) {
        String name = path.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
