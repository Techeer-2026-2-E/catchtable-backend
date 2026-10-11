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
class WaitingMigrationTest {

    @Autowired DataSource dataSource;

    @Test
    void upgradePreservesExistingTicketsAndMarksUnknownLegacyReasons() throws Exception {
        String schema = "waiting_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("SET search_path TO " + schema + ", public");
                try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
                    var previous = migrations.filter(path -> path.toString().endsWith(".sql"))
                            .filter(path -> version(path) < 12).sorted(Comparator.comparingInt(WaitingMigrationTest::version))
                            .toList();
                    for (Path migration : previous) {
                        sql.execute(Files.readString(migration));
                    }
                }
                String seed = Files.readAllLines(Path.of("scripts/local-seed.sql")).stream()
                        .filter(line -> !line.startsWith("\\")).collect(Collectors.joining("\n"));
                sql.execute(seed);
                sql.execute("""
                        insert into waiting (member_id, store_id, waiting_date, party_count, wait_number,
                                             status, created_at, updated_at)
                        values (1, 1, '2026-10-10', 2, 1, 'EXPIRED', now(), now()),
                               (1, 1, '2026-10-11', 3, 1, 'WAITING', now(), now())
                        """);
                sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V12__add_waiting_termination.sql")));
                try (var rows = sql.executeQuery("select * from waiting order by waiting_date")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("NO_SHOW");
                    assertThat(rows.getString("end_reason")).isEqualTo("LEGACY_EXPIRED");
                    assertThat(rows.getObject("ended_at")).isNull();
                    assertThat(rows.getInt("party_count")).isEqualTo(2);
                    assertThat(rows.getInt("wait_number")).isEqualTo(1);
                    assertThat(rows.getLong("version")).isZero();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("WAITING");
                    assertThat(rows.getString("end_reason")).isNull();
                    assertThat(rows.getInt("party_count")).isEqualTo(3);
                    assertThat(rows.next()).isFalse();
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
