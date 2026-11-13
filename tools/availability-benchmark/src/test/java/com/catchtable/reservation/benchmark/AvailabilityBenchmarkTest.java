package com.catchtable.reservation.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Explicitly added by benchmark.init.gradle; not part of the normal test source set. */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.catchtable.reservation.benchmark.AvailabilityBenchmarkTest$SqlCapture",
        "logging.level.root=WARN", "logging.level.com.catchtable=WARN",
        "spring.flyway.default-schema=availability_benchmark",
        "spring.flyway.schemas=availability_benchmark",
        "spring.jpa.properties.hibernate.default_schema=availability_benchmark",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55431/ct_improve_a?currentSchema=availability_benchmark,public",
        "reservation.refund-outbox.scheduling-enabled=false",
        "payment.reconcile-interval-ms=86400000", "waiting.expiry-interval-ms=86400000",
        "reservation.completion-interval-ms=86400000", "reservation.hold-expiry-interval-ms=86400000"
})
@AutoConfigureMockMvc
@Import(AvailabilityBenchmarkTest.FixedClock.class)
class AvailabilityBenchmarkTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T09:00:00+09:00");
    private static final OffsetDateTime FIRST = NOW.plusDays(1);
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void captureIdenticalRequests() throws Exception {
        Path out = Path.of(System.getenv("AVAILABILITY_BENCH_OUT"));
        Files.createDirectories(out);
        int storeCount = setting("STORES", 3);
        int tableCount = setting("TABLES", 40);
        int days = setting("DAYS", 14);
        Long owner = member("OWNER");
        Long customer = member("CUSTOMER");
        Long target = null;
        for (int storeIndex = 0; storeIndex < storeCount; storeIndex++) {
            Long store = jdbc.queryForObject("""
                    INSERT INTO store (owner_id, name, category, address, reservation_duration_minutes,
                        reservation_slot_minutes, arrival_grace_minutes, booking_deadline_minutes)
                    VALUES (?, 'benchmark', 'KOREAN', 'synthetic', 60, 15, 10, 0) RETURNING id
                    """, Long.class, owner);
            if (target == null) target = store;
            jdbc.update("""
                    INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                    SELECT ?, day, '09:00', '23:00' FROM generate_series(1,7) day
                    """, store);
            jdbc.update("""
                    INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                    SELECT ?, n, CASE WHEN n %% 5 = 0 THEN 3 ELSE 1 END, 4,
                        CASE WHEN n %% 10 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
                    FROM generate_series(1, ?) n
                    """.replace("%%", "%"), store, tableCount);
            jdbc.update("""
                    INSERT INTO reservation (member_id, store_id, table_id, party_size,
                        reservation_start_at, reservation_end_at, status, requested_at, confirmed_at, hold_expires_at)
                    SELECT ?, ?, st.id, 2, CAST(? AS TIMESTAMPTZ) + d * INTERVAL '1 day' + h * INTERVAL '2 hours',
                        CAST(? AS TIMESTAMPTZ) + d * INTERVAL '1 day' + h * INTERVAL '2 hours' + INTERVAL '1 hour',
                        CASE st.table_number %% 4 WHEN 0 THEN 'CANCELLED' WHEN 1 THEN 'CONFIRMED'
                            WHEN 2 THEN 'PENDING_PAYMENT' ELSE 'EXPIRED' END,
                        CAST(? AS TIMESTAMPTZ) - INTERVAL '10 minutes',
                        CASE WHEN st.table_number %% 4 = 1 THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                        CASE WHEN st.table_number %% 4 IN (2,3) THEN CAST(? AS TIMESTAMPTZ) - INTERVAL '5 minutes' ELSE NULL END
                    FROM store_table st CROSS JOIN generate_series(0, ? - 1) d
                        CROSS JOIN generate_series(0,5) h WHERE st.store_id = ?
                    """.replace("%%", "%"), customer, store, FIRST, FIRST, NOW, NOW, NOW, days, store);
        }
        for (String table : List.of("member", "store", "business_hour", "special_business_day", "store_table", "reservation")) {
            jdbc.execute("ANALYZE " + table);
        }
        int warmup = setting("WARMUP", 50);
        int samples = setting("SAMPLES", 200);
        List<Map<String, Object>> raw = new ArrayList<>();
        JsonNode expected = null;
        List<String> countSql = null;
        List<String> requestSql = null;
        for (int i = -warmup; i < samples; i++) {
            SqlCapture.start();
            long started = System.nanoTime();
            int status;
            String body;
            List<String> sql;
            try {
                var result = mvc.perform(get("/api/user/stores/{storeId}/availability", target)
                        .param("date", FIRST.toLocalDate().toString()).param("partySize", "2")).andReturn();
                status = result.getResponse().getStatus();
                body = result.getResponse().getContentAsString();
            } finally {
                sql = SqlCapture.finish();
            }
            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
            List<String> counts = sql.stream().filter(SqlCapture::isCount).toList();
            if (status == 200) {
                JsonNode response = mapper.readTree(body);
                if (expected == null) {
                    expected = response;
                    countSql = counts;
                    requestSql = sql;
                }
                assertThat(response).isEqualTo(expected);
            }
            if (i >= 0) raw.add(Map.of("sample", i, "status", status, "elapsedMs", elapsed,
                    "totalSql", sql.size(), "countSql", counts.size()));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("samples.json").toFile(), raw);
        assertThat(expected).isNotNull();
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("response.json").toFile(), expected);
        Files.writeString(out.resolve("request.sql"), String.join(";\n\n", requestSql));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("jvm", System.getProperty("java.vm.name"));
        metadata.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
        metadata.put("storeCount", storeCount);
        metadata.put("tableCount", storeCount * tableCount);
        metadata.put("reservationCount", storeCount * tableCount * days * 6);
        metadata.put("slots", expected.path("slots").size());
        metadata.put("clock", NOW.toString());
        metadata.put("warmup", warmup);
        metadata.put("samples", samples);
        metadata.put("databaseCounters", jdbc.queryForList("SELECT xact_commit, xact_rollback, blks_read, blks_hit, temp_bytes, deadlocks FROM pg_stat_database WHERE datname = current_database()"));
        metadata.put("lockWaitersAtEnd", jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE database = (SELECT oid FROM pg_database WHERE datname = current_database()) AND NOT granted", Long.class));
        metadata.put("scope", "sequential MockMvc request with its service transaction; Hibernate StatementInspector SQL preparations; excludes fixture, transaction control, background threads and network");
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("metadata.json").toFile(), metadata);
        // No EXPLAIN ANALYZE during phase-1 smoke. Phase 2 explains the actual captured count SQL.
        if (Boolean.parseBoolean(System.getenv("AVAILABILITY_BENCH_EXPLAIN"))) {
            List<Object> plans = new ArrayList<>();
            for (int i = 0; i < countSql.size(); i++) {
                String sql = countSql.get(i);
                List<Object> args = new ArrayList<>();
                if (sql.contains("slot_index")) {
                    for (JsonNode slot : expected.path("slots")) {
                        OffsetDateTime start = OffsetDateTime.parse(slot.path("reservationStartAt").asText());
                        args.add(start); args.add(start.plusHours(1));
                    }
                    args.addAll(List.of(target, 2, 2, NOW));
                } else {
                    OffsetDateTime start = OffsetDateTime.parse(expected.path("slots").get(i).path("reservationStartAt").asText());
                    args.addAll(List.of(target, 2, 2, NOW, start, start.plusHours(1)));
                }
                plans.add(jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql,
                        (rs, row) -> rs.getString(1), args.toArray()));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("plans.json").toFile(), plans);
        }
        assertThat(raw).allMatch(row -> row.get("status").equals(200));
        assertThat(expected.path("slots").size()).isEqualTo(53);
    }

    private Long member(String type) {
        return jdbc.queryForObject("INSERT INTO member (email, password, name, phone, user_type) VALUES (?, 'synthetic', 'synthetic', '000', ?) RETURNING id",
                Long.class, type + "-availability@example.invalid", type);
    }
    private static int setting(String name, int fallback) {
        String value = System.getenv("AVAILABILITY_BENCH_" + name);
        return value == null ? fallback : Integer.parseInt(value);
    }
    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock benchmarkClock() { return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul")); }
    }
    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();
        static void start() { SQL.set(new ArrayList<>()); }
        static List<String> finish() { List<String> sql = SQL.get(); SQL.remove(); return sql; }
        static boolean isCount(String sql) { return sql.contains("tstzrange") && sql.contains("count("); }
        @Override public String inspect(String sql) {
            if (SQL.get() != null) SQL.get().add(sql);
            return sql;
        }
    }
}
