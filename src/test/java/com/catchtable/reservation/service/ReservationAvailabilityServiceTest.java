package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.dto.AvailabilityResponse;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ReservationAvailabilityServiceTest {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationAvailabilityService availabilityService;

    @Test
    void availabilityAndCreationUseTheSameBusinessHoursCapacityAndOccupancyRules() {
        Member owner = member("availability-owner@example.com", UserType.OWNER);
        Member customer = member("availability-customer@example.com", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(customer);
        Store store = Store.builder()
                .owner(owner)
                .name("availability store")
                .category(StoreCategory.KOREAN)
                .address("Seoul")
                .reservationDurationMinutes(60)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();
        entityManager.persist(store);
        entityManager.flush();

        LocalDate date = LocalDate.now(STORE_ZONE).plusDays(7);
        jdbcTemplate.update("""
                INSERT INTO business_hour
                    (store_id, day_of_week, open_time, closing_time, break_start_time, break_end_time)
                VALUES (?, ?, '10:00', '16:00', '12:00', '13:00')
                """, store.getId(), date.getDayOfWeek().getValue());
        Long smallTableId = jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 2, 'ACTIVE') RETURNING id
                """, Long.class, store.getId());
        jdbcTemplate.update("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 2, 3, 4, 'ACTIVE')
                """, store.getId());

        OffsetDateTime ten = at(date, 10, 0);
        reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, smallTableId, ten, ten.plusHours(1), 2, OffsetDateTime.now()));

        AvailabilityResponse availability = availabilityService.getAvailability(store.getId(), date, 2);
        assertThat(slot(availability, LocalTime.of(10, 0)).availableTableCount()).isZero();
        assertThat(slot(availability, LocalTime.of(10, 30)).available()).isFalse();
        assertThat(slot(availability, LocalTime.of(11, 0)).availableTableCount()).isEqualTo(1);
        assertThat(availability.slots()).noneMatch(s -> s.reservationStartAt().toLocalTime().equals(LocalTime.NOON));
        assertThat(availability.slots()).noneMatch(s -> s.reservationStartAt().toLocalTime().equals(LocalTime.of(11, 30)));

        OffsetDateTime thirteen = at(date, 13, 0);
        assertThat(availabilityService.getAvailableTableCount(store.getId(), thirteen, 2).availableTableCount())
                .isEqualTo(1);
        assertThat(reservationService.createReservation(
                customer.getId(), new CreateReservationRequest(store.getId(), thirteen, 2)).tableId())
                .isEqualTo(smallTableId);
        assertThat(availabilityService.getAvailableTableCount(store.getId(), thirteen, 2).availableTableCount())
                .isZero();

        assertThatThrownBy(() -> reservationService.createReservation(
                customer.getId(), new CreateReservationRequest(store.getId(), at(date, 12, 0), 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> availabilityService.getAvailableTableCount(store.getId(), at(date.plusDays(1), 10, 0), 2))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> reservationService.createReservation(customer.getId(),
                new CreateReservationRequest(store.getId(), at(date, 11, 0), 5)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> availabilityService.getAvailability(
                store.getId(), LocalDate.now(STORE_ZONE).plusDays(31), 2))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> reservationService.createReservation(customer.getId(),
                new CreateReservationRequest(store.getId(), at(date.plusDays(31), 10, 0), 2)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void overnightOpeningFromPreviousDayIsAvailableAfterMidnight() {
        Member owner = member("overnight-owner@example.com", UserType.OWNER);
        entityManager.persist(owner);
        Store store = Store.builder()
                .owner(owner).name("overnight store").category(StoreCategory.KOREAN).address("Seoul")
                .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10)
                .build();
        entityManager.persist(store);
        entityManager.flush();

        LocalDate saturday = LocalDate.now(STORE_ZONE).with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                VALUES (?, 5, '18:00', '02:00')
                """, store.getId());
        jdbcTemplate.update("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 2, 'ACTIVE')
                """, store.getId());

        AvailabilityResponse availability = availabilityService.getAvailability(store.getId(), saturday, 2);
        assertThat(slot(availability, LocalTime.of(0, 30)).availableTableCount()).isEqualTo(1);
        assertThat(slot(availability, LocalTime.of(1, 0)).available()).isTrue();
        assertThat(availability.slots()).noneMatch(s -> s.reservationStartAt().toLocalTime().equals(LocalTime.of(1, 30)));
        assertThat(availabilityService.getAvailableTableCount(store.getId(), at(saturday, 0, 30), 2)
                .availableTableCount()).isEqualTo(1);
    }

    private static Member member(String email, UserType type) {
        return Member.builder().email(email).password("password").name("test")
                .phone("010-0000-0000").userType(type).build();
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(STORE_ZONE).toOffsetDateTime();
    }

    private static AvailabilityResponse.Slot slot(AvailabilityResponse response, LocalTime time) {
        return response.slots().stream()
                .filter(s -> s.reservationStartAt().toLocalTime().equals(time))
                .findFirst().orElseThrow();
    }
}
