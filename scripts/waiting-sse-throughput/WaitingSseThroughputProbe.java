package com.catchtable.probe;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.durability.*;
import com.catchtable.notification.repository.*;
import com.catchtable.notification.service.NotificationSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Small causal scheduler comparison, NOT a live HTTP/SSE or final performance benchmark. */
public class WaitingSseThroughputProbe {
    static final String DATABASE = "jdbc:postgresql://127.0.0.1:55436/ct_sse_l";
    static final int ROOTS = 60, WARMUP = 16;
    static final long FIXED_DELAY_MS = 100;

    public static void main(String[] args) throws Exception {
        String schema = args[0]; Path output = Path.of(args[1]); int connectionCount = Integer.parseInt(args[2]);
        if (!schema.matches("l_tput_[a-z0-9_]+") || connectionCount < 0 || connectionCount > 1)
            throw new IllegalArgumentException("Only new owned schemas and 0/1 recording connections");
        if (Files.exists(output)) throw new IllegalArgumentException("Do not overwrite evidence");
        try (var owner = DriverManager.getConnection(DATABASE, "catchtable", "catchtable"); var statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema); // Existing schema is an error; never clean/drop.
        }
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(DATABASE + "?currentSchema=" + schema + ",public");
            pool.setUsername("catchtable"); pool.setPassword("catchtable"); pool.setMaximumPoolSize(2); pool.setMinimumIdle(2);
            var counted = new CountingDataSource(pool);
            Flyway.configure().dataSource(counted).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(counted);
            var clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
            var mapper = new ObjectMapper().findAndRegisterModules();
            var properties = new WaitingSseProperties(true, true, 100000, 10000, 16384, 30);
            var connections = new SseEmitterRepository();
            var sent = new AtomicInteger();
            if (connectionCount == 1) connections.save(1L, new SseEmitter() {
                @Override public void send(SseEventBuilder event) {
                    require(!TransactionSynchronizationManager.isActualTransactionActive(), "send has a TX");
                    require(counted.active.get() == 0, "send holds JDBC connection");
                    sent.incrementAndGet();
                }
            });
            var manager = new DataSourceTransactionManager(counted);
            var journal = new WaitingSseJournal(new WaitingSseJournalRepository(jdbc), mapper, clock, properties,
                    new NotificationSettingService(null, null, clock), connections, manager);
            var worker = new WaitingSseWorker(journal, properties, connections, mapper, clock);
            var transaction = new TransactionTemplate(manager);
            for (int i = 0; i < WARMUP; i++) { enqueue(journal, transaction); worker.runOnce(); }
            require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE status='DONE'", Integer.class) == WARMUP, "warmup");
            for (int i = 0; i < ROOTS; i++) enqueue(journal, transaction);
            require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE status='PENDING'", Integer.class) == ROOTS, "backlog");
            int initialSends = sent.get();
            var polls = new ArrayList<Map<String,Object>>();
            var completed = new CompletableFuture<Long>();
            var scheduled = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "causal-scheduler"));
            long start = System.nanoTime();
            try {
                scheduled.scheduleWithFixedDelay(() -> {
                    long entered = System.nanoTime();
                    try {
                        counted.measuring.set(true);
                        worker.scheduled();
                        long returned = System.nanoTime();
                        counted.measuring.set(false);
                        int done = jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE status='DONE'", Integer.class) - WARMUP;
                        polls.add(Map.of("start_ns", entered-start, "duration_ns", returned-entered, "done", done));
                        if (done == ROOTS) completed.complete(returned-start);
                    } catch (Throwable failure) { completed.completeExceptionally(failure); }
                    finally { counted.measuring.set(false); }
                }, 0, FIXED_DELAY_MS, TimeUnit.MILLISECONDS);
                long elapsed = completed.get(30, TimeUnit.SECONDS);
                scheduled.shutdown(); require(scheduled.awaitTermination(5, TimeUnit.SECONDS), "scheduler shutdown");
                int total = ROOTS + WARMUP;
                require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE status<>'DONE'", Integer.class) == 0, "residual root");
                require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_target", Integer.class) == total, "target count");
                require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_attempt WHERE status='SEND_RETURNED'", Integer.class) == total*connectionCount, "attempts");
                require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_observation WHERE phase='UNKNOWN'", Integer.class) == 0, "unknown");
                require(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root r WHERE expected_targets<>(SELECT count(*) FROM waiting_sse_target t WHERE t.root_id=r.id)", Integer.class) == 0, "expansion mismatch");
                require(sent.get()-initialSends == ROOTS*connectionCount, "sends");
                long active = polls.stream().mapToLong(p -> ((Number)p.get("duration_ns")).longValue()).sum();
                var result = new LinkedHashMap<String,Object>();
                result.put("schema", schema); result.put("java", System.getProperty("java.runtime.version"));
                result.put("database", DATABASE); result.put("pool", 2); result.put("warmup_roots", WARMUP);
                result.put("backlog_roots", ROOTS); result.put("recording_connections", connectionCount);
                result.put("fixed_delay_ms", FIXED_DELAY_MS); result.put("drain_ms", elapsed/1e6);
                result.put("worker_active_ms", active/1e6); result.put("gap_and_observer_ms", (elapsed-active)/1e6);
                result.put("polls", polls); result.put("worker_sql_calls", counted.sql.get());
                result.put("worker_commits", counted.commits.get()); result.put("worker_rollbacks", counted.rollbacks.get());
                result.put("sends", sent.get()-initialSends); result.put("pending_unknown_mismatch", 0);
                result.put("scope", "Real journal/repository/worker and PostgreSQL, recording emitter; no HTTP, customer receipt, trigger SQL counts or final F performance claim");
                Files.createDirectories(output.toAbsolutePath().getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
                System.out.println("CAUSAL_RESULT " + mapper.writeValueAsString(result));
            } finally { scheduled.shutdownNow(); require(scheduled.awaitTermination(5, TimeUnit.SECONDS), "scheduler leak"); }
        }
    }
    static void enqueue(WaitingSseJournal journal, TransactionTemplate transaction) {
        transaction.executeWithoutResult(status -> journal.enqueue(new CustomerNotificationEvent(1L, NotificationType.WAITING_CHANGED, 1L, "synthetic")));
    }
    static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }

    /** Counts successful worker JDBC execute/commit/rollback calls only; excludes fixture/audit/migration SQL. */
    static class CountingDataSource extends AbstractDataSource {
        final HikariDataSource delegate;
        final ThreadLocal<Boolean> measuring = ThreadLocal.withInitial(() -> false);
        final AtomicLong sql = new AtomicLong(), commits = new AtomicLong(), rollbacks = new AtomicLong();
        final AtomicInteger active = new AtomicInteger();
        CountingDataSource(HikariDataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user,password)); }
        Connection wrap(Connection connection) {
            active.incrementAndGet(); var closed = new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("close") && closed.compareAndSet(false,true)) active.decrementAndGet();
                    if (measuring.get() && method.getName().equals("commit")) commits.incrementAndGet();
                    if (measuring.get() && method.getName().equals("rollback")) rollbacks.incrementAndGet();
                    if (result instanceof Statement statement) return wrapStatement(statement);
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        Object wrapStatement(Statement statement) {
            Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(statement, args);
                    if (measuring.get() && method.getName().startsWith("execute")) sql.incrementAndGet();
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
