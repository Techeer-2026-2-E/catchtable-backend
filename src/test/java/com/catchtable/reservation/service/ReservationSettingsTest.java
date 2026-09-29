package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.global.exception.GlobalExceptionHandler;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.store.controller.OwnerStoreTableController;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.StoreTableUpdateRequest;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.store.service.BusinessHourService;
import com.catchtable.store.service.StoreTableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// 서비스 트랜잭션이 실제로 커밋·롤백되도록 테스트 전체에는 @Transactional을 붙이지 않는다.
@SpringBootTest
class ReservationSettingsTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationService reservations;
    @Autowired private OwnerReservationService ownerReservations;
    @Autowired private ReservationAvailabilityService availability;
    @Autowired private StoreTableService tables;
    @Autowired private BusinessHourService hours;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long ownerId;
    private Long customerId;
    private Long storeId;
    private Long tableId;
    private OffsetDateTime start;

    @BeforeEach
    void setUp() {
        ownerId = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('owner', '010-0000-0001', 'OWNER')
                RETURNING id
                """, Long.class);
        customerId = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('customer', '010-0000-0002', 'CUSTOMER')
                RETURNING id
                """, Long.class);
        storeId = jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'settings store', 'KOREAN', 'Seoul', 120, 30, 10) RETURNING id
                """, Long.class, ownerId);
        tableId = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 6, 'ACTIVE') RETURNING id
                """, Long.class, storeId);
        jdbc.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '08:00', '22:00' FROM generate_series(1, 7) AS day
                """, storeId);
        start = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(7)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store_table WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", ownerId, customerId);
    }

    static Stream<StoreTableUpdateRequest> conflictingTableChanges() {
        return Stream.of(
                new StoreTableUpdateRequest(2, null, 2, null),
                new StoreTableUpdateRequest(2, 5, null, null),
                new StoreTableUpdateRequest(2, null, null, TableStatus.INACTIVE));
    }

    @ParameterizedTest
    @MethodSource("conflictingTableChanges")
    void rejectsConflictingTableChangesAndRollsBackEveryField(StoreTableUpdateRequest change) {
        reserve();
        assertThatThrownBy(() -> tables.updateTable(storeId, tableId, change))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TABLE_HAS_RESERVATIONS);
        var table = tables.getTables(storeId).getFirst();
        assertThat(table.tableNumber()).isEqualTo(1);
        assertThat(table.minCapacity()).isEqualTo(1);
        assertThat(table.capacity()).isEqualTo(6);
        assertThat(table.status()).isEqualTo(TableStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "late_open", "early_close", "break"})
    void rejectsConflictingHoursAndRestoresOriginalWeek(String change) {
        reserve();
        BusinessHourUpdateRequest request = switch (change) {
            case "closed" -> new BusinessHourUpdateRequest(List.of());
            case "late_open" -> businessHours("11:00", "22:00", null, null);
            case "early_close" -> businessHours("08:00", "11:00", null, null);
            default -> businessHours("08:00", "22:00", "10:30", "11:30");
        };
        assertThatThrownBy(() -> hours.replaceBusinessHours(storeId, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);
        assertThat(hours.getBusinessHours(storeId)).hasSize(7).allSatisfy(hour -> {
            assertThat(hour.openTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(hour.closingTime()).isEqualTo(LocalTime.of(22, 0));
            assertThat(hour.breakStartTime()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_hour WHERE store_id = ?",
                Integer.class, storeId)).isEqualTo(7);
    }

    @Test
    void compatibleChangesAndCancelledReservationsDoNotBlockSettings() {
        Long id = reserve();
        var table = tables.updateTable(storeId, tableId, new StoreTableUpdateRequest(null, 4, 4, null));
        assertThat(table.minCapacity()).isEqualTo(4);
        assertThat(table.capacity()).isEqualTo(4);
        assertThat(hours.replaceBusinessHours(storeId, businessHours("07:00", "23:00", null, null)))
                .hasSize(1);

        ownerReservations.cancelReservation(ownerId, id, "매장 사정");
        assertThat(tables.updateTable(storeId, tableId,
                new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE)).status())
                .isEqualTo(TableStatus.INACTIVE);
        assertThat(hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of()))).isEmpty();
    }

    @Test
    void protectsOngoingReservationsButIgnoresFinishedPeriods() {
        Long id = reserve();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("UPDATE reservation SET reservation_start_at = ?, reservation_end_at = ? WHERE id = ?",
                now.minusMinutes(30), now.plusMinutes(30), id);
        assertThatThrownBy(() -> tables.updateTable(storeId, tableId,
                new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE)))
                .extracting("errorCode").isEqualTo(ErrorCode.TABLE_HAS_RESERVATIONS);
        assertThatThrownBy(() -> hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of())))
                .extracting("errorCode").isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);

        jdbc.update("UPDATE reservation SET reservation_start_at = ?, reservation_end_at = ? WHERE id = ?",
                now.minusHours(3), now.minusHours(1), id);
        assertThat(hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of()))).isEmpty();
        assertThat(tables.updateTable(storeId, tableId,
                new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE)).status())
                .isEqualTo(TableStatus.INACTIVE);
    }

    @Test
    void validatesOvernightReservationsAgainstThePreviousBusinessDay() {
        start = start.withHour(0);
        var previousDay = start.toLocalDate().minusDays(1).getDayOfWeek();
        hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of(
                new BusinessHourRequest(previousDay, LocalTime.of(18, 0), LocalTime.of(2, 0), null, null))));
        reserve();
        assertThatThrownBy(() -> hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of(
                new BusinessHourRequest(previousDay, LocalTime.of(18, 0), LocalTime.of(1, 0), null, null)))))
                .extracting("errorCode").isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);
        assertThat(hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of(
                new BusinessHourRequest(previousDay, LocalTime.of(18, 0), LocalTime.of(3, 0), null, null)))))
                .hasSize(1);
    }

    @Test
    void legacySecondPrecisionHoursBlockBookingUntilTheOwnerCorrectsThem() {
        start = start.withHour(14);
        jdbc.update("""
                UPDATE business_hour SET open_time = '10:00', closing_time = '10:00:01'
                WHERE store_id = ? AND day_of_week = ?
                """, storeId, start.getDayOfWeek().getValue());
        assertThatThrownBy(() -> availability.getAvailability(storeId, start.toLocalDate(), 4))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BUSINESS_HOUR_PRECISION);
        assertThatThrownBy(this::reserve)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BUSINESS_HOUR_PRECISION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE store_id = ?",
                Integer.class, storeId)).isZero();

        hours.replaceBusinessHours(storeId, businessHours("09:00", "22:00", null, null));
        assertThat(reserve()).isNotNull();
    }


    @ParameterizedTest
    @CsvSource({"hours,true", "hours,false", "table,true", "table,false"})
    @Timeout(20)
    void reservationAndSettingsCannotCommitAConflict(String setting, boolean settingsFirst) throws Exception {
        Runnable change = setting.equals("hours")
                ? () -> hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of()))
                : () -> tables.updateTable(storeId, tableId,
                        new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE));
        Runnable create = this::reserve;
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                (settingsFirst ? change : create).run();
                firstReady.countDown();
                await(releaseFirst);
            }));
            await(firstReady);
            var second = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        secondReady.countDown();
                        (settingsFirst ? create : change).run();
                    });
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });
            await(secondReady);
            if (settingsFirst && setting.equals("table")) {
                // 배정은 SKIP LOCKED이므로 변경 중인 마지막 테이블에는 기다리지 않고 409를 반환한다.
                assertThat(second.get(5, TimeUnit.SECONDS)).isInstanceOf(ResponseStatusException.class);
            } else {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (!waiting && !second.isDone() && System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE pid = ? AND wait_event_type = 'Lock')
                            """, Boolean.class, secondPid.get()));
                    if (!waiting) Thread.sleep(10);
                }
                assertThat(waiting).as("second transaction waits for the first transaction's row lock").isTrue();
            }
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            RuntimeException failure = second.get(5, TimeUnit.SECONDS);
            if (settingsFirst) {
                assertThat(failure).isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(
                                setting.equals("hours") ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT));
            } else {
                assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(setting.equals("hours")
                                ? ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS : ErrorCode.TABLE_HAS_RESERVATIONS));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE store_id = ?",
                    Integer.class, storeId)).isEqualTo(settingsFirst ? 0 : 1);
            assertThat(tables.getTables(storeId).getFirst().status())
                    .isEqualTo(settingsFirst && setting.equals("table") ? TableStatus.INACTIVE : TableStatus.ACTIVE);
            assertThat(hours.getBusinessHours(storeId)).hasSize(settingsFirst && setting.equals("hours") ? 0 : 7);
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        }
    }

    private Long reserve() {
        return reservations.createReservation(customerId, new CreateReservationRequest(storeId, start, 4))
                .reservationId();
    }

    private BusinessHourUpdateRequest businessHours(String open, String close, String breakStart, String breakEnd) {
        return new BusinessHourUpdateRequest(List.of(new BusinessHourRequest(start.getDayOfWeek(),
                LocalTime.parse(open), LocalTime.parse(close),
                breakStart == null ? null : LocalTime.parse(breakStart),
                breakEnd == null ? null : LocalTime.parse(breakEnd))));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent operation did not start");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
