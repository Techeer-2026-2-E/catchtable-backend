package com.catchtable.waiting;

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
class WaitingEventMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void upgradePreservesSourcesWithoutBackfillAndQueryDistinguishesLegacyAndNew() throws Exception {
        String schema = "waiting_event_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("SET search_path TO " + schema + ", public");
                try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
                    for (Path migration : paths.filter(p -> p.toString().endsWith(".sql"))
                            .filter(p -> version(p) < 36).sorted(Comparator.comparingInt(WaitingEventMigrationTest::version)).toList()) {
                        sql.execute(Files.readString(migration));
                    }
                }
                sql.execute(Files.readAllLines(Path.of("scripts/local-seed.sql")).stream()
                        .filter(line -> !line.startsWith("\\")).collect(Collectors.joining("\n")));
                sql.execute("""
                        INSERT INTO waiting(id, member_id, store_id, waiting_date, party_count, wait_number,
                          queue_order, status, call_count, recall_count, created_at, updated_at)
                        VALUES (900001,1,1,'2026-11-10',2,1,1,'NO_SHOW',3,2,now(),now());
                        INSERT INTO waiting_end_history(waiting_id,previous_status,status,reason,detail,actor_id,created_at)
                        VALUES(900001,'CALLED','NO_SHOW','NO_RESPONSE','private reason',1,'2026-11-10T12:05:00+09:00');
                        INSERT INTO waiting_defer_history(waiting_id,before_position,after_position,actor_id,created_at)
                        VALUES(900001,1,2,1,'2026-11-10T12:00:00+09:00');
                        INSERT INTO alimtalk_outbox(id,waiting_id,member_id,call_count,recall_count,transport_mode,
                          recipient,store_name,wait_number,expires_at,status,attempt_count,next_attempt_at,created_at,updated_at)
                        VALUES('00000000-0000-0000-0000-000000000001',900001,1,3,2,'simulation','01000000000',
                          'private store',1,now(),'UNKNOWN',1,now(),'2026-11-10T12:01:00+09:00','2026-11-10T12:02:00+09:00');
                        """);
                String before = snapshot(sql);
                sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V36__create_waiting_event.sql")));
                assertThat(snapshot(sql)).isEqualTo(before);
                try (var rows = sql.executeQuery("SELECT count(*) FROM waiting_event")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
                String query = Files.readString(Path.of("scripts/waiting-event-history.sql"));
                sql.execute("PREPARE timeline(bigint,bigint,integer) AS " + query);
                try (var rows = sql.executeQuery("EXECUTE timeline(900001,1,100)")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("record_origin")).isEqualTo("ORIGINAL_DEFER_HISTORY");
                    assertThat(rows.getObject("call_count")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("record_origin")).isEqualTo("CURRENT_OUTBOX_SNAPSHOT");
                    assertThat(rows.getString("details")).doesNotContain("01000000000", "private store");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("record_origin")).isEqualTo("ORIGINAL_END_HISTORY");
                    assertThat(rows.getString("details")).doesNotContain("private reason");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("EXECUTE timeline(900001,9999,100)")) { assertThat(rows.next()).isFalse(); }
                sql.execute("UPDATE alimtalk_outbox SET status='SENT', updated_at=now() WHERE waiting_id=900001");
                sql.execute("UPDATE alimtalk_outbox SET status='SENT', updated_at=now() WHERE waiting_id=900001");
                try (var rows = sql.executeQuery("SELECT event_type,details FROM waiting_event")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("ALIMTALK_SENT");
                    assertThat(rows.getString(2)).contains("UNKNOWN").doesNotContain("01000000000");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("EXECUTE timeline(900001,1,1)")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.next()).isFalse();
                }
                sql.execute("DEALLOCATE timeline");
            } finally {
                sql.execute("ROLLBACK");
                sql.execute("RESET search_path");
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static String snapshot(java.sql.Statement sql) throws Exception {
        StringBuilder data = new StringBuilder();
        for (String table : new String[]{"waiting", "waiting_end_history", "waiting_defer_history", "alimtalk_outbox"}) {
            try (var rows = sql.executeQuery("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id")) {
                while (rows.next()) data.append(rows.getString(1));
            }
        }
        return data.toString();
    }

    private static int version(Path path) {
        String name = path.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
