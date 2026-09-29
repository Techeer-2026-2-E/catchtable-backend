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

}
