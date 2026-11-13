package com.catchtable.waiting.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

// External harness copied identically into isolated baseline/modified snapshots; never a normal test.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.address=127.0.0.1", "server.port=18083", "waiting.expiry-interval-ms=3600000", "alimtalk.enabled=true",
        "alimtalk.mode=simulation", "alimtalk.initial-delay-ms=3600000", "payment.reconcile-interval-ms=3600000",
        "reservation.completion-interval-ms=3600000", "reservation.hold-expiry-interval-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=12", "spring.jpa.properties.hibernate.generate_statistics=false",
        "logging.level.com.catchtable=WARN", "spring.jpa.properties.hibernate.format_sql=false"})
@Import(WaitingHistoryBenchmark.FixedTime.class)
class WaitingHistoryBenchmark {
    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock benchmarkClock() {
            return Clock.fixed(Instant.parse("2031-01-20T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired PlatformTransactionManager transactionManager;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ThreadPoolExecutor observationWriter;
    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    @Test
    void measure() throws Exception {
        if (!"true".equals(System.getenv("RUN_WAITING_HISTORY_BENCHMARK"))) {
            throw new IllegalStateException("Stage 2 only: coordinator must schedule this benchmark");
        }
        if (!"bench_c".equals(jdbc.queryForObject("select current_schema()", String.class))) {
            throw new IllegalStateException("Use only the assigned DB's disposable bench_c schema");
        }
        int workers = parameter("BENCH_WORKERS", 4, 1);
        int size = parameter("BENCH_WAITINGS_PER_STORE", 10, 1);
        int warmup = parameter("BENCH_WARMUP_PER_WORKER", 20, 0);
        int samples = parameter("BENCH_SAMPLES_PER_WORKER", 200, 1);
        int repeats = parameter("BENCH_REPEATS", 3, 1);
        if (warmup % 4 != 0 || samples % 4 != 0) throw new IllegalArgumentException("Use complete four-request cycles");
        Path output = Path.of(System.getenv("BENCH_OUTPUT")).toAbsolutePath();
        Files.createDirectories(output);
        if (!"all".equals(jdbc.queryForObject("show pg_stat_statements.track", String.class))) {
            throw new IllegalStateException("pg_stat_statements.track=all is required");
        }
        if (jdbc.queryForObject("select to_regclass('bench_c.waiting_event') is not null", Boolean.class)) {
            Object bean = context.getBean(Class.forName("com.catchtable.notification.service.WaitingSseObservationRecorder"));
            observationWriter = (ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(bean, "writer");
        }
        write(output, "jvm.json", Map.of("arguments", ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "processors", os.getAvailableProcessors(),
                "os", System.getProperty("os.name"), "java", System.getProperty("java.version"),
                "clock", "2031-01-20T03:00:00Z", "sseConnections", 0, "alimtalkMode", "simulation"));
        var transactions = new TransactionCounts();
        if (!(transactionManager instanceof ConfigurableTransactionManager configurable)) {
            throw new IllegalStateException("Transaction execution counters require a configurable manager");
        }
        configurable.addListener(transactions);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (int repeat = 0; repeat < repeats; repeat++) {
            jdbc.execute("TRUNCATE bench_c.member CASCADE");
            List<Target> targets = fixtures(workers, size);
            write(output, "fixtures-" + repeat + ".json", targets);
            List<Sample> warm = run(targets, warmup);
            drainObservations();
            write(output, "warmup-" + repeat + ".json", warm);
            if (warm.stream().anyMatch(s -> s.status != 200)) throw new IllegalStateException("Warmup HTTP errors; raw data preserved");
            var before = databaseSnapshot();
            write(output, "state-before-" + repeat + ".json", before);
            jdbc.execute("select public.pg_stat_statements_reset()");
            var transactionsBefore = transactions.snapshot();
            List<Map<String, Object>> resources = new CopyOnWriteArrayList<>();
            var monitor = Executors.newSingleThreadScheduledExecutor();
            var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            monitor.scheduleAtFixedRate(() -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("at", Instant.now().toString());
                try {
                    row.put("connectionsActive", pool.getActiveConnections());
                    row.put("connectionsWaiting", pool.getThreadsAwaitingConnection());
                    row.put("heapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                    row.put("hostCpuLoad", os.getCpuLoad());
                    row.put("processCpuLoad", os.getProcessCpuLoad());
                    row.put("hostLoadAverage", os.getSystemLoadAverage());
                    row.put("hostFreeMemoryBytes", os.getFreeMemorySize());
                    row.put("observationQueued", observationWriter == null ? 0 : observationWriter.getQueue().size());
                    row.put("lockWaiters", jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'", Long.class));
                } catch (RuntimeException e) { row.put("error", e.toString()); }
                resources.add(row);
            }, 0, 50, TimeUnit.MILLISECONDS);
            var startCpu = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
            long start = System.nanoTime();
            List<Sample> raw;
            long requestsFinished;
            long drained;
            try {
                raw = run(targets, samples);
                requestsFinished = System.nanoTime();
                drainObservations();
                drained = System.nanoTime();
            } finally {
                monitor.shutdown();
                if (!monitor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Monitor did not stop");
            }
            long cpuMillis = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).minus(startCpu).toMillis();
            var transactionsAfter = transactions.snapshot();
            var sql = jdbc.queryForList("""
                    select queryid, toplevel, query, calls, total_exec_time, rows, shared_blks_hit, shared_blks_read,
                           wal_bytes from public.pg_stat_statements where dbid=(select oid from pg_database where datname=current_database())
                    order by total_exec_time desc
                    """);
            var after = databaseSnapshot();
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("repeat", repeat);
            metadata.put("workers", workers);
            metadata.put("waitingRows", workers * size);
            metadata.put("warmupPerWorker", warmup);
            metadata.put("samplesPerWorker", samples);
            metadata.put("requestPhaseSeconds", (requestsFinished - start) / 1e9);
            metadata.put("observationDrainMillis", (drained - requestsFinished) / 1e6);
            metadata.put("elapsedIncludingObservationDrainSeconds", (drained - start) / 1e9);
            metadata.put("cpuMillis", cpuMillis);
            var transactionDelta = new LinkedHashMap<String, Long>();
            transactionsAfter.forEach((key, value) -> transactionDelta.put(key, value - transactionsBefore.get(key)));
            metadata.put("springTransactions", transactionDelta);
            metadata.put("errors", raw.stream().filter(s -> s.status != 200).count());
            metadata.put("errorRate", raw.stream().filter(s -> s.status != 200).count() / (double) raw.size());
            metadata.put("monitorErrors", resources.stream().filter(r -> r.containsKey("error")).count());
            var sorted = raw.stream().map(Sample::millis).sorted().toList();
            metadata.put("p50Millis", percentile(sorted, .50));
            metadata.put("p95Millis", percentile(sorted, .95));
            metadata.put("p99Millis", percentile(sorted, .99));
            metadata.put("stateBefore", before);
            metadata.put("stateAfter", after);
            write(output, "requests-" + repeat + ".json", raw);
            write(output, "resources-" + repeat + ".json", resources);
            write(output, "sql-" + repeat + ".json", sql);
            write(output, "state-after-" + repeat + ".json", after);
            summaries.add(metadata);
            write(output, "summary.json", summaries); // retain previous iterations even if a later iteration fails
            if (raw.stream().anyMatch(s -> s.status != 200) || resources.stream().anyMatch(r -> r.containsKey("error"))) {
                throw new IllegalStateException("HTTP/monitor errors; raw evidence preserved");
            }
        }
    }

    private Map<String, Object> databaseSnapshot() {
        var state = new LinkedHashMap<String, Object>();
        state.put("waiting", jdbc.queryForList("select id,status,call_count,recall_count,called_at,call_expires_at,responded_at,arrival_intent from bench_c.waiting where call_count>0 order by id"));
        state.put("outbox", jdbc.queryForList("select status,attempt_count,count(*) as count from bench_c.alimtalk_outbox group by status,attempt_count order by status,attempt_count"));
        state.put("outboxRows", jdbc.queryForObject("select count(*) from bench_c.alimtalk_outbox", Long.class));
        state.put("eventRows", observationWriter == null ? 0 : jdbc.queryForObject("select count(*) from bench_c.waiting_event", Long.class));
        state.put("eventBytes", observationWriter == null ? 0 : jdbc.queryForObject("select pg_total_relation_size('bench_c.waiting_event')", Long.class));
        state.put("events", observationWriter == null ? List.of() : jdbc.queryForList("select source_type,event_type,count(*) as count from bench_c.waiting_event group by source_type,event_type order by source_type,event_type"));
        return state;
    }

    private List<Target> fixtures(int workers, int size) {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Long owner = member("OWNER");
            Long store = jdbc.queryForObject("""
                    insert into store(owner_id,name,category,address,waiting_available,reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
                    values(?,'benchmark','KOREAN','synthetic',true,90,30,10) returning id
                    """, Long.class, owner);
            for (int n = 1; n <= size; n++) {
                Long member = member("CUSTOMER");
                Long id = jdbc.queryForObject("""
                        insert into waiting(member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,created_at,updated_at)
                        values(?,?,'2031-01-20',2,?,?,'WAITING','2031-01-20T03:00:00Z','2031-01-20T03:00:00Z') returning id
                        """, Long.class, member, store, n, n);
                if (n == 1) targets.add(new Target(i, owner, member, id));
            }
        }
        return targets;
    }

    private Long member(String type) {
        return jdbc.queryForObject("insert into member(name,phone,user_type) values('synthetic','01000000000',?) returning id", Long.class, type);
    }

    private List<Sample> run(List<Target> targets, int count) throws Exception {
        List<Sample> rows = new CopyOnWriteArrayList<>();
        var ready = new CountDownLatch(targets.size());
        var startTogether = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(targets.size())) {
            var futures = targets.stream().map(target -> executor.submit(() -> {
                ready.countDown();
                startTogether.await();
                for (int i = 0; i < count; i++) {
                    String operation = switch (i % 4) { case 1 -> "accept"; case 2 -> "recall"; default -> "call"; };
                    String prefix = operation.equals("accept") ? "user" : "owner";
                    long actor = operation.equals("accept") ? target.customer : target.owner;
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083/api/" + prefix + "/waitings/" + target.waiting + "/" + operation))
                            .header("X-Member-Id", Long.toString(actor)).timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.noBody()).build();
                    long start = System.nanoTime();
                    int status;
                    String response;
                    try {
                        var result = http.send(request, HttpResponse.BodyHandlers.ofString());
                        status = result.statusCode();
                        response = result.body();
                    } catch (Exception e) { status = 0; response = e.getClass().getName(); }
                    rows.add(new Sample(target.worker, i, operation, status, (System.nanoTime() - start) / 1e6, response));
                }
                return true;
            })).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
            startTogether.countDown();
            for (var future : futures) future.get();
        }
        return rows;
    }

    private void drainObservations() throws Exception {
        if (observationWriter == null) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((observationWriter.getActiveCount() != 0 || !observationWriter.getQueue().isEmpty()) && System.nanoTime() < deadline) Thread.sleep(10);
        if (observationWriter.getActiveCount() != 0 || !observationWriter.getQueue().isEmpty()) throw new IllegalStateException("Observation drain timeout");
    }
    private int parameter(String key, int fallback, int minimum) {
        int value = Integer.parseInt(System.getenv().getOrDefault(key, "" + fallback));
        if (value < minimum) throw new IllegalArgumentException(key);
        return value;
    }
    private void write(Path output, String file, Object value) throws Exception {
        Files.writeString(output.resolve(file), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    private double percentile(List<Double> values, double p) { return values.get(Math.max(0, (int) Math.ceil(values.size() * p) - 1)); }
    // Counts Spring-managed new physical transactions only, not participation or JdbcTemplate autocommit.
    // Identical in both variants; no DB queries or instrumentation in production sources.
    private static class TransactionCounts implements TransactionExecutionListener {
        private final AtomicLong committed = new AtomicLong();
        private final AtomicLong rolledBack = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong observationCommitted = new AtomicLong();
        private final AtomicLong observationRolledBack = new AtomicLong();

        @Override public void afterCommit(TransactionExecution execution, Throwable failure) {
            if (!execution.isNewTransaction()) return;
            if (failure != null) { failed.incrementAndGet(); return; }
            committed.incrementAndGet();
            if (Thread.currentThread().getName().equals("waiting-sse-observation")) observationCommitted.incrementAndGet();
        }
        @Override public void afterRollback(TransactionExecution execution, Throwable failure) {
            if (!execution.isNewTransaction()) return;
            if (failure != null) failed.incrementAndGet();
            rolledBack.incrementAndGet();
            if (Thread.currentThread().getName().equals("waiting-sse-observation")) observationRolledBack.incrementAndGet();
        }
        Map<String, Long> snapshot() {
            return Map.of("committed", committed.get(), "rolledBack", rolledBack.get(), "failed", failed.get(),
                    "observationCommitted", observationCommitted.get(), "observationRolledBack", observationRolledBack.get());
        }
    }
    record Target(int worker, long owner, long customer, long waiting) {}
    record Sample(int worker, int index, String operation, int status, double millis, String response) {}
}
