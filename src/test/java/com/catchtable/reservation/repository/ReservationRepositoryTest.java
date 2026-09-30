package com.catchtable.reservation.repository;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ReservationRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationService reservationService;

    @Test
    void adjacentReservationsAreAllowedButOverlappingReservationIsRejected() {
        Member owner = Member.builder()
                .email("owner@example.com")
                .password("password")
                .name("owner")
                .phone("010-0000-0001")
                .userType(UserType.OWNER)
                .build();
        Member customer = Member.builder()
                .email("customer@example.com")
                .password("password")
                .name("customer")
                .phone("010-0000-0002")
                .userType(UserType.CUSTOMER)
                .build();
        entityManager.persist(owner);
        entityManager.persist(customer);

        Store store = Store.builder()
                .owner(owner)
                .name("store")
                .category(StoreCategory.KOREAN)
                .address("Seoul")
                .reservationDurationMinutes(120)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();
        entityManager.persist(store);
        entityManager.flush();

        Long tableId = jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, 1, 4, 'ACTIVE')
                RETURNING id
                """, Long.class, store.getId());

        OffsetDateTime firstStart = OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(10)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        OffsetDateTime firstEnd = firstStart.plusHours(2);
        OffsetDateTime confirmedAt = firstStart.minusDays(1);

        reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, firstStart, firstEnd, 2, confirmedAt));
        reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, firstEnd, firstEnd.plusHours(2), 2, confirmedAt));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(Reservation.confirmed(
                customer,
                store,
                tableId,
                firstStart.plusMinutes(30),
                firstEnd.plusMinutes(30),
                2,
                confirmedAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }


    @Test
    void automaticallyAssignsTheSmallestAvailableActiveTable() {
        Member owner = Member.builder()
                .email("allocation-owner@example.com")
                .password("password")
                .name("owner")
                .phone("010-0000-0031")
                .userType(UserType.OWNER)
                .build();
        Member customer = Member.builder()
                .email("allocation-customer@example.com")
                .password("password")
                .name("customer")
                .phone("010-0000-0032")
                .userType(UserType.CUSTOMER)
                .build();
        entityManager.persist(owner);
        entityManager.persist(customer);

        Store store = Store.builder()
                .owner(owner)
                .name("allocation store")
                .category(StoreCategory.KOREAN)
                .address("Seoul")
                .reservationDurationMinutes(120)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();
        entityManager.persist(store);
        entityManager.flush();

        Long twoSeatTableId = insertTable(store.getId(), 1, 2);
        Long fourSeatTableId = insertTable(store.getId(), 2, 4);
        Long sixSeatTableId = insertTable(store.getId(), 3, 6);
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '08:00', '22:00' FROM generate_series(1, 7) AS day
                """, store.getId());
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(7)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        ReservationResponse first = reservationService.createReservation(
                customer.getId(), new CreateReservationRequest(store.getId(), start, 3));
        ReservationResponse second = reservationService.createReservation(
                customer.getId(), new CreateReservationRequest(store.getId(), start, 3));

        assertThat(first.tableId()).isEqualTo(fourSeatTableId);
        assertThat(second.tableId()).isEqualTo(sixSeatTableId);
        assertThat(first.tableId()).isNotEqualTo(twoSeatTableId);

        jdbcTemplate.update("UPDATE store_table SET status = 'INACTIVE' WHERE id = ?", sixSeatTableId);
        OffsetDateTime nextSlot = start.plusDays(1);
        assertThatThrownBy(() -> reservationService.createReservation(
                customer.getId(), new CreateReservationRequest(store.getId(), nextSlot, 5)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private Long insertTable(Long storeId, int tableNumber, int capacity) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """, Long.class, storeId, tableNumber, capacity);
    }
}
