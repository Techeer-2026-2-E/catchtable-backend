package com.catchtable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LocalSeedTest {

    @Autowired private DataSource dataSource;

    private Connection connection;
    private Statement sql;
    private String schema;
    private String seed;

    @BeforeEach
    void setUp() throws Exception {
        // 고정 목 ID와 시퀀스는 별도 스키마에서 검사해 다른 테스트 데이터에 영향을 주지 않는다.
        schema = "seed_test_" + UUID.randomUUID().toString().replace("-", "");
        connection = dataSource.getConnection();
        sql = connection.createStatement();
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("SET search_path TO " + schema + ", public");
        try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : migrations.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                sql.execute(Files.readString(migration));
            }
        }
        // psql 전용 메타 명령만 제거하고, 실제 배포할 seed SQL의 BEGIN/COMMIT까지 그대로 실행한다.
        seed = Files.readAllLines(Path.of("scripts/local-seed.sql")).stream()
                .filter(line -> !line.startsWith("\\"))
                .collect(Collectors.joining("\n"));
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (connection != null) {
            try (Connection opened = connection; Statement statement = sql) {
                if (statement != null) {
                    statement.execute("ROLLBACK");
                    statement.execute("RESET search_path");
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    @Test
    void seedsAnEmptyDatabaseAndCanBeRepeatedWithoutChangingRows() throws Exception {
        sql.execute(seed);
        List<String> before = rows();
        assertThat(before).hasSize(13); // 회원 2, 매장 1, 테이블 3, 영업일 7
        sql.execute(seed);
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE member SET email = 'existing-customer@example.test' WHERE id = 1",
            "UPDATE member SET email = 'existing-owner@example.test' WHERE id = 2",
            "UPDATE store SET name = 'existing store' WHERE id = 1",
            "UPDATE store_table SET capacity = 8 WHERE id = 3",
            "UPDATE business_hour SET closing_time = '21:00' WHERE day_of_week = 1",
            "UPDATE business_hour SET deleted_at = now()",
            "UPDATE business_hour SET deleted_at = now() WHERE day_of_week = 1",
            "DELETE FROM business_hour WHERE day_of_week = 1"
    })
    void collisionsRollBackEveryInsertedRowAndKeepExistingData(String collision) throws Exception {
        sql.execute(seed);
        sql.execute(collision);
        sql.execute("DELETE FROM store_table WHERE id = 1");
        List<String> before = rows();
        assertThatThrownBy(() -> sql.execute(seed))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("기존 로컬 데이터가 목 데이터와 충돌합니다.");
        sql.execute("ROLLBACK");
        assertThat(rows()).isEqualTo(before);
    }

    private List<String> rows() throws SQLException {
        List<String> rows = new ArrayList<>();
        for (String table : List.of("member", "store", "store_table", "business_hour")) {
            try (var result = sql.executeQuery("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id")) {
                while (result.next()) rows.add(table + ":" + result.getString(1));
            }
        }
        return rows;
    }
}
