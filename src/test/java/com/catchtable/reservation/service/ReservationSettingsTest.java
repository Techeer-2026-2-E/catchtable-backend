package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.global.exception.GlobalExceptionHandler;
import com.catchtable.global.auth.LoginMemberArgumentResolver;
import com.catchtable.reservation.controller.ReservationController;
import com.catchtable.reservation.controller.OwnerReservationController;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.ReservationHoldResponse;
import com.catchtable.reservation.dto.OwnerReservationUpdateRequest;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.controller.OwnerStoreTableController;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.StoreTableUpdateRequest;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.store.service.BusinessHourService;
import com.catchtable.store.service.StoreTableService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalTime;
import java.time.Duration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

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
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SseEmitterRepository emitterRepository;

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

    @Test
    void holdApiKeepsTheServerDeadlineAndProtectsItsOwnerAndTable() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ReservationController(reservations))
                .setCustomArgumentResolvers(new LoginMemberArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var request = new CreateReservationRequest(storeId, start, 4);
        String body = objectMapper.writeValueAsString(request);
        mvc.perform(post("/api/user/reservations/holds").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/user/reservations/holds").header("X-Member-Id", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(storeId, start, 0))))
                .andExpect(status().isBadRequest());

        var result = mvc.perform(post("/api/user/reservations/holds").header("X-Member-Id", customerId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();
        var hold = objectMapper.readValue(result.getResponse().getContentAsString(), ReservationHoldResponse.class);
        assertThat(hold.holdId()).isEqualTo(hold.reservationId());
        assertThat(hold.remainingSeconds()).isBetween(290L, 300L);
        OffsetDateTime requestedAt = jdbc.queryForObject("SELECT requested_at FROM reservation WHERE id = ?",
                OffsetDateTime.class, hold.holdId());
        assertThat(Duration.between(requestedAt, hold.expiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM reservation WHERE id = ?",
                OffsetDateTime.class, hold.holdId())).isNull();
        var reloaded = objectMapper.readValue(mvc.perform(get("/api/user/reservations/holds/{id}", hold.holdId())
                        .header("X-Member-Id", customerId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), ReservationHoldResponse.class);
        assertThat(reloaded.expiresAt().toInstant()).isEqualTo(hold.expiresAt().toInstant());
        mvc.perform(get("/api/user/reservations/holds/{id}", hold.holdId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user/reservations/holds/{id}", hold.holdId()).header("X-Member-Id", ownerId))
                .andExpect(status().isNotFound());

        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isZero();
        assertThatThrownBy(this::reserve).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        // 애플리케이션 배정 경로를 우회해도 DB가 선점·확정 예약 간 중복을 막는다.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at,
                    reservation_end_at, party_size, status, requested_at, confirmed_at)
                SELECT member_id, store_id, table_id, reservation_start_at, reservation_end_at,
                    party_size, 'CONFIRMED', requested_at, requested_at FROM reservation WHERE id = ?
                """, hold.holdId())).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE reservation SET requested_at = requested_at - INTERVAL '10 minutes',
                    hold_expires_at = hold_expires_at - INTERVAL '10 minutes' WHERE id = ?
                """, hold.holdId());
        // 정리 작업 실행 전에도 가용 조회와 재예약이 만료를 반영한다.
        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isEqualTo(1);
        Long confirmedId = reserve();
        mvc.perform(get("/api/user/reservations/holds/{id}", hold.holdId()).header("X-Member-Id", customerId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.remainingSeconds").value(0));
        mvc.perform(get("/api/user/reservations/holds/{id}", confirmedId).header("X-Member-Id", customerId))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> reservations.createHold(customerId, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void expiryBoundaryReleasesHoldsAndNoLongerBlocksSettings() {
        var hold = reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4));
        assertThatThrownBy(() -> tables.updateTable(storeId, tableId,
                new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE)))
                .extracting("errorCode").isEqualTo(ErrorCode.TABLE_HAS_RESERVATIONS);
        assertThatThrownBy(() -> hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of())))
                .extracting("errorCode").isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);

        var transaction = new TransactionTemplate(transactionManager);
        assertThat(transaction.<Integer>execute(s -> reservationRepository.expireHolds(storeId, hold.expiresAt().minusNanos(1000))))
                .isZero();
        assertThat(transaction.<Integer>execute(s -> reservationRepository.expireHolds(storeId, hold.expiresAt())))
                .isEqualTo(1);
        assertThat(reservations.getHold(customerId, hold.holdId()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(transaction.<Integer>execute(s -> reservationRepository.expireHolds(storeId, hold.expiresAt())))
                .isZero();
        assertThat(tables.updateTable(storeId, tableId,
                new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE)).status())
                .isEqualTo(TableStatus.INACTIVE);
        assertThat(hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of()))).isEmpty();
    }

    @Test
    void getAndScheduledExpiryDoNotReleaseConfirmedReservations() {
        var first = reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4));
        var second = reservations.createHold(customerId, new CreateReservationRequest(storeId, start.plusHours(2), 4));
        var confirmed = reservations.createHold(customerId, new CreateReservationRequest(storeId, start.plusHours(4), 4));
        // 결제 확정 상태의 픽스처. 결제 승인 API는 아직 연결하지 않는다.
        jdbc.update("UPDATE reservation SET status = 'CONFIRMED', confirmed_at = requested_at WHERE id = ?",
                confirmed.holdId());
        jdbc.update("""
                UPDATE reservation SET requested_at = requested_at - INTERVAL '10 minutes',
                    hold_expires_at = hold_expires_at - INTERVAL '10 minutes' WHERE store_id = ?
                """, storeId);
        assertThat(reservations.getHold(customerId, first.holdId()).status()).isEqualTo(ReservationStatus.EXPIRED);
        reservations.expireHolds();
        assertThat(reservations.getHold(customerId, second.holdId()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.getHold(customerId, confirmed.holdId()).status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(availability.getAvailableTableCount(storeId, start.plusHours(4), 4).availableTableCount()).isZero();
    }

    @Test
    @Timeout(10)
    void expirySkipsALockedReservationUntilItsStateChangeFinishes() throws Exception {
        var hold = reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4));
        jdbc.update("""
                UPDATE reservation SET requested_at = requested_at - INTERVAL '10 minutes',
                    hold_expires_at = hold_expires_at - INTERVAL '10 minutes' WHERE id = ?
                """, hold.holdId());
        try (var lock = jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM reservation WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, hold.holdId());
                statement.executeQuery().close();
            }
            reservations.expireHolds();
            assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, hold.holdId()))
                    .isEqualTo("PENDING_PAYMENT");
            assertThatThrownBy(this::reserve).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            lock.commit();
        }
        assertThat(reserve()).isNotNull();
        assertThat(reservations.getHold(customerId, hold.holdId()).status()).isEqualTo(ReservationStatus.EXPIRED);
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
    @ValueSource(booleans = {false, true})
    @Timeout(20)
    void concurrentTableNumbersReturnConflictEvenWhenThePrecheckPasses(boolean update) throws Exception {
        Long otherTableId = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, 2, 6, 'ACTIVE') RETURNING id
                """, Long.class, storeId);
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerStoreTableController(tables))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var executor = Executors.newFixedThreadPool(2);
        try (var lock = jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            // SELECT 사전 검사는 통과시키고 INSERT/UPDATE만 멈춰 두 요청이 반드시 경합하게 한다.
            try (var statement = lock.createStatement()) {
                statement.execute("LOCK TABLE store_table IN SHARE MODE");
            }
            List<Future<MvcResult>> requests = new ArrayList<>();
            for (Long id : List.of(tableId, otherTableId)) {
                requests.add(executor.submit(() -> mvc.perform((update
                                ? patch("/api/owner/stores/{storeId}/tables/{id}", storeId, id)
                                : post("/api/owner/stores/{storeId}/tables", storeId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update ? "{\"tableNumber\":9}" : "{\"tableNumber\":9,\"capacity\":6}"))
                        .andReturn()));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int blocked = 0;
            while (blocked < 2 && System.nanoTime() < deadline) {
                blocked = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                          AND (lower(query) LIKE '%insert%into%store_table%'
                               OR lower(query) LIKE '%update%store_table%')
                        """, Integer.class);
                if (blocked < 2) Thread.sleep(10);
            }
            lock.commit();
            assertThat(blocked).as("both writes passed their duplicate-number check").isEqualTo(2);
            List<Integer> statuses = new ArrayList<>();
            for (var request : requests) {
                MvcResult result = request.get(5, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 409) {
                    jsonPath("$.code").value("DUPLICATE_TABLE_NUMBER").match(result);
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(update ? 200 : 201, 409);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM store_table WHERE store_id = ? AND table_number = 9
                    """, Integer.class, storeId)).isEqualTo(1);
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"hours,true,false", "hours,false,false", "table,true,false", "table,false,false",
            "hours,true,true", "hours,false,true", "table,true,true", "table,false,true"})
    @Timeout(20)
    void reservationAndSettingsCannotCommitAConflict(String setting, boolean settingsFirst, boolean hold) throws Exception {
        Runnable change = setting.equals("hours")
                ? () -> hours.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(List.of()))
                : () -> tables.updateTable(storeId, tableId,
                        new StoreTableUpdateRequest(null, null, null, TableStatus.INACTIVE));
        Runnable create = hold
                ? () -> reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4))
                : this::reserve;
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

    @Test
    void explicitHoldReleaseIsOwnedIdempotentAndNeverCancelsAConfirmedReservation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ReservationController(reservations))
                .setCustomArgumentResolvers(new LoginMemberArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var hold = reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4));
        mvc.perform(delete("/api/user/reservations/holds/{id}", hold.holdId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/user/reservations/holds/{id}", hold.holdId()).header("X-Member-Id", ownerId))
                .andExpect(status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/api/user/reservations/holds/{id}", hold.holdId()).header("X-Member-Id", customerId))
                    .andExpect(status().isNoContent());
        }
        assertThat(reservations.getHold(customerId, hold.holdId()).status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isEqualTo(1);
        var next = reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4));
        assertThat(next.holdId()).isNotEqualTo(hold.holdId());
        jdbc.update("UPDATE reservation SET status = 'CONFIRMED', confirmed_at = requested_at WHERE id = ?", next.holdId());
        mvc.perform(delete("/api/user/reservations/holds/{id}", next.holdId()).header("X-Member-Id", customerId))
                .andExpect(status().isConflict());
        assertThat(reservations.getHold(customerId, next.holdId()).status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isZero();
    }

    @Test
    void releasingAnExpiredHoldKeepsTheExpiryAndRollbackKeepsAnActiveHold() {
        var held = reservations.createHold(customerId, new CreateReservationRequest(storeId, start, 4));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            reservations.releaseHold(customerId, held.holdId());
            transaction.setRollbackOnly();
        });
        assertThat(reservations.getHold(customerId, held.holdId()).status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        jdbc.update("""
                UPDATE reservation SET requested_at = requested_at - INTERVAL '10 minutes',
                    hold_expires_at = hold_expires_at - INTERVAL '10 minutes' WHERE id = ?
                """, held.holdId());
        reservations.releaseHold(customerId, held.holdId());
        reservations.releaseHold(customerId, held.holdId());
        assertThat(reservations.getHold(customerId, held.holdId()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reserve()).isNotNull();
    }

    @Test
    void changeApiValidatesOwnerAndPartialInputAndNotifiesOnlyOnceOnRetry() throws Exception {
        Long id = reserve();
        var original = reservationRepository.findById(id).orElseThrow();
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerReservationController(ownerReservations))
                .setCustomArgumentResolvers(new LoginMemberArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = "/api/owner/reservations/" + id;
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(path).header("X-Member-Id", customerId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                .andExpect(status().isForbidden());
        for (String body : List.of("{}", "{\"partySize\":0}", "{\"reservationStartAt\":\"bad-date\"}")) {
            mvc.perform(patch(path).header("X-Member-Id", ownerId).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('other', '010-0000-0099', 'OWNER') RETURNING id
                """, Long.class);
        SseEmitter emitter = mock(SseEmitter.class);
        emitterRepository.save(customerId, emitter);
        try {
            mvc.perform(patch(path).header("X-Member-Id", otherOwner)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                    .andExpect(status().isNotFound());
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(patch(path).header("X-Member-Id", ownerId)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.partySize").value(2));
            }
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            var changed = reservationRepository.findById(id).orElseThrow();
            assertThat(changed.getReservationStartAt()).isEqualTo(original.getReservationStartAt());
            assertThat(changed.getRequestedAt()).isEqualTo(original.getRequestedAt());
            assertThat(changed.getConfirmedAt()).isEqualTo(original.getConfirmedAt());
        } finally {
            emitterRepository.delete(customerId, emitter);
            jdbc.update("DELETE FROM member WHERE id = ?", otherOwner);
        }
    }

    @Test
    void changingAnOverlappingTimeExcludesItselfAndReleasesTheOldSlotOnCommit() {
        Long id = reserve();
        var overlapping = ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(start.plusHours(1), null));
        assertThat(overlapping.tableId()).isEqualTo(tableId);
        assertThat(overlapping.reservationStartAt().toInstant()).isEqualTo(start.plusHours(1).toInstant());
        assertThat(overlapping.reservationEndAt().toInstant()).isEqualTo(start.plusHours(3).toInstant());

        var moved = ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(start.plusHours(4), null));
        assertThat(moved.reservationId()).isEqualTo(id);
        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isEqualTo(1);
        assertThat(availability.getAvailableTableCount(storeId, start.plusHours(4), 4).availableTableCount()).isZero();
    }

    @Test
    void increasingPartySizeReassignsATableInTheSameStore() {
        Long id = reserve();
        Long bigger = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 2, 4, 8, 'ACTIVE') RETURNING id
                """, Long.class, storeId);
        var changed = ownerReservations.updateReservation(ownerId, id, new OwnerReservationUpdateRequest(null, 8));
        assertThat(changed.storeId()).isEqualTo(storeId);
        assertThat(changed.reservationId()).isEqualTo(id);
        assertThat(changed.tableId()).isEqualTo(bigger);
        assertThat(changed.partySize()).isEqualTo(8);
        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void blockedDestinationKeepsOriginalReservationAndSlot(boolean hold) {
        Long id = reserve();
        var original = reservations.getReservation(customerId, id);
        var destination = new CreateReservationRequest(storeId, start.plusHours(4), 4);
        if (hold) reservations.createHold(customerId, destination);
        else reservations.createReservation(customerId, destination);

        assertThatThrownBy(() -> ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(destination.reservationStartAt(), 4)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(reservations.getReservation(customerId, id)).isEqualTo(original);
        assertThat(availability.getAvailableTableCount(storeId, start, 4).availableTableCount()).isZero();
    }

    @Test
    void closedHoursAndInvalidSlotChangesKeepOriginalReservation() {
        Long id = reserve();
        var original = reservations.getReservation(customerId, id);
        for (var invalidTime : List.of(start.withHour(23), start.plusMinutes(1))) {
            assertThatThrownBy(() -> ownerReservations.updateReservation(ownerId, id,
                    new OwnerReservationUpdateRequest(invalidTime, null)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(reservations.getReservation(customerId, id)).isEqualTo(original);
    }

    @Test
    void onlyAnUnenteredConfirmedReservationCanBeChanged() {
        Long checkedIn = reserve();
        ownerReservations.confirmVisit(ownerId, checkedIn);
        Long canceled = reservations.createReservation(customerId,
                new CreateReservationRequest(storeId, start.plusHours(2), 4)).reservationId();
        ownerReservations.cancelReservation(ownerId, canceled, "매장 사정");
        Long pending = reservations.createHold(customerId,
                new CreateReservationRequest(storeId, start.plusHours(4), 4)).holdId();
        for (Long id : List.of(checkedIn, canceled, pending)) {
            var original = reservations.getReservation(customerId, id);
            assertThatThrownBy(() -> ownerReservations.updateReservation(ownerId, id,
                    new OwnerReservationUpdateRequest(start.plusDays(1), 2)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_MODIFIABLE));
            assertThat(reservations.getReservation(customerId, id)).isEqualTo(original);
        }
    }

    @Test
    void anUnenteredPastReservationCanMoveToAFutureSlot() {
        Long id = reserve();
        backdateReservation(id);

        var changed = ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(start.plusDays(1), 2));

        assertThat(changed.reservationId()).isEqualTo(id);
        assertThat(changed.reservationStartAt().toInstant()).isEqualTo(start.plusDays(1).toInstant());
        assertThat(changed.partySize()).isEqualTo(2);
        assertThat(changed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservations.getReservation(customerId, id).reservationStartAt())
                .isEqualTo(changed.reservationStartAt());
    }

    @Test
    void changingOnlyPartySizeKeepsAPastReservationsScheduleAndReassignsItsTable() {
        Long id = reserve();
        backdateReservation(id);
        var original = reservations.getReservation(customerId, id);
        Long largerTable = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 2, 1, 8, 'ACTIVE') RETURNING id
                """, Long.class, storeId);
        jdbc.update("UPDATE store SET reservation_duration_minutes = 60 WHERE id = ?", storeId);

        var changed = ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(null, 7));

        assertThat(changed.partySize()).isEqualTo(7);
        assertThat(changed.tableId()).isEqualTo(largerTable);
        assertThat(changed.reservationStartAt()).isEqualTo(original.reservationStartAt());
        assertThat(changed.reservationEndAt()).isEqualTo(original.reservationEndAt());
        assertThat(ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(original.reservationStartAt(), 7))).isEqualTo(changed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aPastReservationCannotMoveToAnOccupiedFutureSlot(boolean hold) {
        Long id = reserve();
        backdateReservation(id);
        var original = reservations.getReservation(customerId, id);
        var destination = new CreateReservationRequest(storeId, start, 4);
        if (hold) reservations.createHold(customerId, destination);
        else reservations.createReservation(customerId, destination);

        assertThatThrownBy(() -> ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(start, 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(reservations.getReservation(customerId, id)).isEqualTo(original);
    }

    @Test
    void aPastReservationCannotChangeItsStartToAnotherPastTime() {
        Long id = reserve();
        backdateReservation(id);
        var original = reservations.getReservation(customerId, id);

        assertThatThrownBy(() -> ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(original.reservationStartAt().minusMinutes(30), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(reservations.getReservation(customerId, id)).isEqualTo(original);
    }

    @Test
    void aPastReservationKeepsItsPartySizeAndTableWhenNoLargerTableIsAvailable() {
        Long id = reserve();
        backdateReservation(id);
        var original = reservations.getReservation(customerId, id);

        assertThatThrownBy(() -> ownerReservations.updateReservation(ownerId, id,
                new OwnerReservationUpdateRequest(null, 7)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(reservations.getReservation(customerId, id)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(15)
    void changeAndNewBookingCannotClaimTheSameDestination(boolean hold) throws Exception {
        Long id = reserve();
        OffsetDateTime destination = start.plusHours(4);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startRace = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var change = executor.submit(() -> attemptReservationAction(ready, startRace,
                    () -> ownerReservations.updateReservation(ownerId, id, new OwnerReservationUpdateRequest(destination, 4))));
            var create = executor.submit(() -> attemptReservationAction(ready, startRace, () -> {
                var request = new CreateReservationRequest(storeId, destination, 4);
                if (hold) reservations.createHold(customerId, request);
                else reservations.createReservation(customerId, request);
            }));
            await(ready);
            startRace.countDown();
            boolean changed = change.get(10, TimeUnit.SECONDS);
            boolean created = create.get(10, TimeUnit.SECONDS);
            assertThat(changed).isNotEqualTo(created);
            assertThat(reservations.getReservation(customerId, id).reservationStartAt().toInstant())
                    .isEqualTo((changed ? destination : start).toInstant());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE store_id = ? AND reservation_start_at = ?",
                Integer.class, storeId, destination)).isEqualTo(1);
    }

    private boolean attemptReservationAction(CountDownLatch ready, CountDownLatch startRace, Runnable action) {
        ready.countDown();
        await(startRace);
        try {
            action.run();
            return true;
        } catch (ResponseStatusException exception) {
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            return false;
        }
    }

    private Long reserve() {
        return reservations.createReservation(customerId, new CreateReservationRequest(storeId, start, 4))
                .reservationId();
    }

    private void backdateReservation(Long id) {
        jdbc.update("""
                UPDATE reservation SET reservation_start_at = now() - INTERVAL '1 minute',
                    reservation_end_at = now() + INTERVAL '119 minutes' WHERE id = ?
                """, id);
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
