package com.catchtable.reservation;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReservationRefundMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void populatedV22WithoutV20StillRejectsUpgradeBeforeAnyNewSchemaChange() throws Exception {
        String schema = "refund_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Path oldMigrations = Files.createTempDirectory("catchtable-v22-without-v20-");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            try {
                try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
                    for (Path path : paths.toList()) {
                        int version = Integer.parseInt(path.getFileName().toString().split("__")[0].substring(1));
                        if (version <= 22 && version != 20) Files.copy(path, oldMigrations.resolve(path.getFileName()));
                    }
                }
                Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                        .locations("filesystem:" + oldMigrations).load().migrate();
                sql.execute("SET search_path TO " + schema + ", public");
                sql.execute("INSERT INTO member(name, phone, user_type) VALUES ('upgrade', '000', 'CUSTOMER')");
                assertThatThrownBy(() -> Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                        .locations("filesystem:src/main/resources/db/migration").load().migrate())
                        .isInstanceOf(FlywayException.class)
                        .hasMessageContaining("20");
                try (var rows = sql.executeQuery("SELECT count(*) FROM member")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows = sql.executeQuery("SELECT max(version::int) FROM flyway_schema_history WHERE success")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(22);
                }
                try (var rows = sql.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema + "' AND table_name = 'reservation' AND column_name IN ('deposit_amount_snapshot', 'refund_in_progress')")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            } finally {
                sql.execute("RESET search_path");
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                try (var paths = Files.walk(oldMigrations)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
}
