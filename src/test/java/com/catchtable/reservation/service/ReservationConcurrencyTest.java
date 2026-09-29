package com.catchtable.reservation.service;

import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.entity.Reservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReservationConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationService reservationService;

    @Test
    void assignsTheSmallestAvailableTable() {
        String suffix = UUID.randomUUID().toString();
        Long ownerId = insertMember("owner-" + suffix + "@example.com", "OWNER");
        Long customerId = insertMember("customer-" + suffix + "@example.com", "CUSTOMER");
        Long storeId = jdbcTemplate.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, 'allocation store', 'KOREAN', 'Seoul', false, 120, 30, 10)
                RETURNING id
                """, Long.class, ownerId);
        Long smallTableId = insertTable(storeId, 1, 2);
        Long fittingTableId = insertTable(storeId, 2, 4);
        Long nextTableId = insertTable(storeId, 3, 6);
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '08:00', '22:00' FROM generate_series(1, 7) AS day
                """, storeId);
        OffsetDateTime startAt = OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = new CreateReservationRequest(storeId, startAt, 3);

        try {
            Reservation first = reservationService.createReservation(customerId, request);
            Reservation second = reservationService.createReservation(customerId, request);

            assertThat(first.getTableId()).isEqualTo(fittingTableId).isNotEqualTo(smallTableId);
            assertThat(second.getTableId()).isEqualTo(nextTableId);
        } finally {
            jdbcTemplate.update("DELETE FROM reservation WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store_table WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store WHERE id = ?", storeId);
            jdbcTemplate.update("DELETE FROM member WHERE id IN (?, ?)", customerId, ownerId);
        }
    }

    @Test
    @Timeout(15)
    void onlyOneConcurrentRequestCanReserveTheLastTable() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Long ownerId = insertMember("owner-" + suffix + "@example.com", "OWNER");
        Long customerId = insertMember("customer-" + suffix + "@example.com", "CUSTOMER");
        Long storeId = jdbcTemplate.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, 'concurrency store', 'KOREAN', 'Seoul', false, 120, 30, 10)
                RETURNING id
                """, Long.class, ownerId);
        jdbcTemplate.update("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, 1, 4, 'ACTIVE')
                """, storeId);
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '08:00', '22:00' FROM generate_series(1, 7) AS day
                """, storeId);

        OffsetDateTime startAt = OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(7)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        CreateReservationRequest request = new CreateReservationRequest(storeId, startAt, 4);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Boolean> attempt = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent reservation did not start");
            }
            try {
                reservationService.createReservation(customerId, request);
                return true;
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                    return false;
                }
                throw exception;
            }
        };

        try {
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat((first.get() ? 1 : 0) + (second.get() ? 1 : 0)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM reservation WHERE store_id = ?", Long.class, storeId
            )).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("DELETE FROM reservation WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store_table WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store WHERE id = ?", storeId);
            jdbcTemplate.update("DELETE FROM member WHERE id IN (?, ?)", customerId, ownerId);
        }
    }

    private Long insertMember(String email, String userType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'member', ?, ?)
                RETURNING id
                """, Long.class, email, "010-" + email.hashCode(), userType);
    }

    private Long insertTable(Long storeId, int tableNumber, int capacity) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """, Long.class, storeId, tableNumber, capacity);
    }
}
