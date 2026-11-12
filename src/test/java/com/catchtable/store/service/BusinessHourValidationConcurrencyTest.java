package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.store.dto.SpecialBusinessDayRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.catchtable.store.service.BusinessHourValidationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

// 각 actor는 별도 실제 DB 연결/트랜잭션이다. sleep으로 경합을 추측하지 않고 pg_blocking_pids로 확인한다.
@SpringBootTest
@Import(BusinessHourValidationConcurrencyTest.Config.class)
@Timeout(25)
class BusinessHourValidationConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessHourService hours;
    @Autowired SpecialBusinessDayService specials;
    @Autowired ReservationService reservations;
    @Autowired PlatformTransactionManager transactionManager;
    private BusinessHourValidationFixture fixture;

    enum Change { WEEKLY, SPECIAL_SAVE, SPECIAL_DELETE }

    @TestConfiguration
    static class Config {
        @Bean @Primary Clock validationClock() { return Clock.fixed(NOW, ZONE); }
    }
    @BeforeEach void setUp() { fixture = new BusinessHourValidationFixture(jdbc); }
    @AfterEach void tearDown() { fixture.close(); }

    @ParameterizedTest @EnumSource(Change.class)
    void committedChangeRejectsConcurrentReservation(Change change) throws Exception {
        race(change, true, false);
    }
    @ParameterizedTest @EnumSource(Change.class)
    void committedReservationRejectsConcurrentChange(Change change) throws Exception {
        race(change, false, false);
    }
    @ParameterizedTest @EnumSource(Change.class)
    void rolledBackChangeAllowsWaitingReservation(Change change) throws Exception {
        race(change, true, true);
    }
    @ParameterizedTest @EnumSource(Change.class)
    void rolledBackReservationAllowsWaitingChange(Change change) throws Exception {
        race(change, false, true);
    }

    private void race(Change change, boolean changeFirst, boolean rollbackFirst) throws Exception {
        if (change == Change.SPECIAL_DELETE) {
            hours.replaceBusinessHours(fixture.storeId, weekly("13:00", "22:00"));
            specials.saveSpecialBusinessDay(fixture.storeId, DATE,
                    new SpecialBusinessDayRequest(false, LocalTime.of(8, 0), LocalTime.of(22, 0), null, null));
        }
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setTimeout(15);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                if (changeFirst) change(change); else reserve();
                firstApplied.countDown();
                await(releaseFirst);
                if (rollbackFirst) status.setRollbackOnly();
            }));
            assertThat(firstApplied.await(8, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> second = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        if (changeFirst) reserve(); else change(change);
                    });
                    return null;
                } catch (RuntimeException error) {
                    return error;
                }
            });
            awaitDatabaseLock(secondPid, firstPid.get());
            assertThat(secondPid.get()).isNotEqualTo(firstPid.get());
            assertThat(second.isDone()).isFalse();
            releaseFirst.countDown();
            first.get(8, TimeUnit.SECONDS);
            Throwable result = second.get(8, TimeUnit.SECONDS);
            if (rollbackFirst) {
                assertThat(result).isNull();
            } else if (changeFirst) {
                assertThat(result).isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
            } else {
                assertThat(result).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS));
            }
            boolean reservationSurvives = changeFirst == rollbackFirst;
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE store_id = ?", Long.class, fixture.storeId))
                    .isEqualTo(reservationSurvives ? 1L : 0L);
            assertThat(hours.isOpenBetween(fixture.storeId, DATE.atTime(10, 0), DATE.atTime(12, 0)))
                    .isEqualTo(reservationSurvives);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitDatabaseLock(AtomicInteger waitingPid, int blockingPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            if (waitingPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE pid = ? AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)))
                    """, Boolean.class, waitingPid.get(), blockingPid))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected separate connection to wait on the first transaction's store lock");
    }

    private void change(Change change) {
        switch (change) {
            case WEEKLY -> hours.replaceBusinessHours(fixture.storeId, weekly("13:00", "22:00"));
            case SPECIAL_SAVE -> specials.saveSpecialBusinessDay(fixture.storeId, DATE,
                    new SpecialBusinessDayRequest(true, null, null, null, null));
            case SPECIAL_DELETE -> specials.deleteSpecialBusinessDay(fixture.storeId, DATE);
        }
    }
    private void reserve() {
        reservations.createReservation(fixture.customerId, new CreateReservationRequest(fixture.storeId, at(DATE, 10), 2));
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for commit gate");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
