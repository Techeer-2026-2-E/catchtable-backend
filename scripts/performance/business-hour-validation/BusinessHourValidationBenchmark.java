package com.catchtable.store.service;

import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.store.dto.SpecialBusinessDayRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

import static com.catchtable.store.service.BusinessHourValidationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 명시적으로 등록하는 별도 Gradle task에서만 실행한다. HTTP가 아닌 서비스+트랜잭션 시간이다. */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=6",
        "spring.jpa.show-sql=false", "logging.level.root=WARN",
        "reservation.completion-interval-ms=3600000", "reservation.hold-expiry-interval-ms=3600000"
})
@Import(BusinessHourValidationBenchmark.Config.class)
class BusinessHourValidationBenchmark {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessHourService hours;
    @Autowired SpecialBusinessDayService specials;
    @Autowired ReservationService reservations;
    private static final ThreadLocal<Metric> CURRENT = new ThreadLocal<>();

    enum Operation { WEEKLY, SPECIAL_SAVE, SPECIAL_DELETE }

    @TestConfiguration
    static class Config {
        @Bean @Primary Clock benchmarkClock() { return Clock.fixed(NOW, ZONE); }
        @Bean static BeanPostProcessor measureJdbc() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    if (!(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException {
                            return measuredConnection(super.getConnection());
                        }
                        @Override public Connection getConnection(String user, String password) throws SQLException {
                            return measuredConnection(super.getConnection(user, password));
                        }
                    };
                }
            };
        }
    }

    @Test
    void compareFixedFixtures() throws Exception {
        Path output = Path.of(Objects.requireNonNull(System.getenv("B_BENCH_RAW")));
        Files.createDirectories(output.getParent());
        Files.writeString(output, "variant,repeat,operation,reservations,mode,sample,actor,request_ms,sql_count,config_selects,lock_acquire_ms,lock_hold_ms,observed_lock_wait_ms,wait_samples,pid,error\n");
        int warmup = Integer.parseInt(env("B_BENCH_WARMUP", "10"));
        int samples = Integer.parseInt(env("B_BENCH_SAMPLES", "100"));
        int repeats = Integer.parseInt(env("B_BENCH_REPEATS", "3"));
        for (int repeat = 0; repeat < repeats; repeat++) {
            for (String size : env("B_BENCH_SIZES", "0,10,1000").split(",")) {
                int count = Integer.parseInt(size);
                for (Operation operation : Operation.values()) {
                    for (boolean contended : new boolean[]{false, true}) {
                        try (var fixture = new BusinessHourValidationFixture(jdbc)) {
                            fixture.activeReservations(count);
                            for (int sample = -warmup; sample < samples; sample++) {
                                reset(fixture, operation);
                                List<Metric> results = measure(fixture, operation, contended);
                                if (sample >= 0) {
                                    for (Metric metric : results) {
                                        Files.writeString(output, metric.csv(repeat, operation, count, contended, sample), StandardOpenOption.APPEND);
                                    }
                                }
                                for (Metric metric : results) {
                                    assertThat(metric.error).as("benchmark request error: %s", metric.error).isEmpty();
                                    assertThat(metric.acquiredAt).as("store lock instrumentation").isPositive();
                                    assertThat(metric.releasedAt).as("commit instrumentation").isGreaterThan(metric.acquiredAt);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void reset(BusinessHourValidationFixture fixture, Operation operation) {
        jdbc.update("DELETE FROM reservation WHERE store_id = ? AND table_id = ?", fixture.storeId, fixture.tableId);
        jdbc.update("DELETE FROM special_business_day WHERE store_id = ?", fixture.storeId);
        jdbc.update("UPDATE business_hour SET open_time = '08:00', closing_time = '22:00' WHERE store_id = ?", fixture.storeId);
        if (operation == Operation.SPECIAL_DELETE) {
            jdbc.update("""
                    INSERT INTO special_business_day (store_id, business_date, closed, open_time, closing_time)
                    VALUES (?, ?, false, '09:00', '21:00')
                    """, fixture.storeId, DATE);
        }
    }

    private List<Metric> measure(BusinessHourValidationFixture fixture, Operation operation, boolean contended) throws Exception {
        Metric writer = new Metric("writer");
        Metric reader = new Metric("reservation");
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch readerReady = new CountDownLatch(1);
        writer.onLock = writerLocked::countDown;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> readerTask = contended ? pool.submit(() -> {
                readerReady.countDown();
                try {
                    if (!writerLocked.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("writer did not lock store");
                    reader.measure(() -> reservations.createReservation(fixture.customerId,
                            new CreateReservationRequest(fixture.storeId, at(DATE, 14), 2)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }) : null;
            if (contended && !readerReady.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("reader did not start");
            Future<?> monitor = contended ? pool.submit(() -> observeWait(reader)) : null;
            writer.measure(() -> {
                switch (operation) {
                    case WEEKLY -> hours.replaceBusinessHours(fixture.storeId, weekly("09:00", "21:00"));
                    case SPECIAL_SAVE -> specials.saveSpecialBusinessDay(fixture.storeId, DATE,
                            new SpecialBusinessDayRequest(false, LocalTime.of(9, 0), LocalTime.of(21, 0), null, null));
                    case SPECIAL_DELETE -> specials.deleteSpecialBusinessDay(fixture.storeId, DATE);
                }
            });
            if (contended) {
                readerTask.get(60, TimeUnit.SECONDS);
                monitor.get(10, TimeUnit.SECONDS);
                assertThat(reader.pid).isNotEqualTo(writer.pid);
            }
            return contended ? List.of(writer, reader) : List.of(writer);
        } finally {
            writerLocked.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("benchmark threads did not stop");
        }
    }

    // 2ms 목표 간격의 관측치: 짧은 대기는 놓칠 수 있으며 SQL 왕복 시간과 같지 않다.
    private void observeWait(Metric reader) {
        long previous = System.nanoTime();
        boolean wasWaiting = false;
        long deadline = previous + TimeUnit.SECONDS.toNanos(60);
        while (!reader.done && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            boolean waiting = reader.pid != 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')
                    """, Boolean.class, reader.pid));
            long now = System.nanoTime();
            if (wasWaiting) reader.observedWaitNanos += now - previous;
            if (waiting) reader.waitSamples++;
            previous = now;
            wasWaiting = waiting;
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static Connection measuredConnection(Connection target) throws SQLException {
        Metric metric = CURRENT.get();
        if (metric == null) return target;
        try {
            Class<?> pgConnection = Class.forName("org.postgresql.PGConnection");
            Object unwrapped = target.unwrap(pgConnection);
            metric.pid = (int) pgConnection.getMethod("getBackendPID").invoke(unwrapped);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Cannot identify benchmark connection", e);
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
            Object result = invoke(method, target, args);
            if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                String sql = ((String) args[0]).toLowerCase(Locale.ROOT);
                return measuredStatement(statement, sql, metric);
            }
            if (method.getName().equals("commit") || method.getName().equals("rollback")) metric.releasedAt = System.nanoTime();
            return result;
        });
    }

    private static PreparedStatement measuredStatement(PreparedStatement target, String sql, Metric metric) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
            if (!method.getName().startsWith("execute")) return invoke(method, target, args);
            metric.sqlCount++;
            if (sql.startsWith("select") && (sql.contains(" from business_hour ") || sql.contains(" from special_business_day "))) metric.configSelects++;
            boolean storeLock = sql.contains(" from store ") && (sql.contains(" for update") || sql.contains(" for no key update") || sql.contains(" for share"));
            long started = System.nanoTime();
            Object result = invoke(method, target, args);
            if (storeLock && metric.acquiredAt == 0) {
                metric.acquiredAt = System.nanoTime();
                metric.lockAcquireNanos = metric.acquiredAt - started;
                metric.onLock.run();
            }
            return result;
        });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    private static class Metric {
        final String actor;
        volatile int pid;
        volatile boolean done;
        int sqlCount;
        int configSelects;
        long requestNanos;
        long lockAcquireNanos;
        long acquiredAt;
        long releasedAt;
        long observedWaitNanos;
        int waitSamples;
        String error = "";
        Runnable onLock = () -> {};
        Metric(String actor) { this.actor = actor; }
        void measure(Runnable action) {
            CURRENT.set(this);
            long start = System.nanoTime();
            try { action.run(); }
            catch (RuntimeException e) { error = e.getClass().getSimpleName(); }
            finally { requestNanos = System.nanoTime() - start; done = true; CURRENT.remove(); }
        }
        String csv(int repeat, Operation operation, int count, boolean contended, int sample) {
            return String.format(Locale.ROOT, "%s,%d,%s,%d,%s,%d,%s,%.6f,%d,%d,%.6f,%.6f,%.6f,%d,%d,%s%n",
                    env("B_BENCH_VARIANT", "unknown"), repeat, operation, count, contended ? "contended" : "single", sample, actor,
                    requestNanos / 1e6, sqlCount, configSelects, lockAcquireNanos / 1e6,
                    acquiredAt == 0 || releasedAt == 0 ? 0 : (releasedAt - acquiredAt) / 1e6,
                    observedWaitNanos / 1e6, waitSamples, pid, error);
        }
    }
}
