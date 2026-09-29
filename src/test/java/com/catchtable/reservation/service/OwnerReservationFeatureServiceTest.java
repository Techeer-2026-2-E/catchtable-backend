package com.catchtable.reservation.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
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
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OwnerReservationFeatureServiceTest {

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private OwnerReservationService ownerReservationService;

    @Test
    void listsOnlyOwnedStoreReservationsForDateInStartOrder() {
        Member owner = member("owner-list@example.com", UserType.OWNER);
        Member customer = member("customer-list@example.com", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(customer);
        Store store = Store.builder().owner(owner).name("owner list store")
                .category(StoreCategory.KOREAN).address("Seoul")
                .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();
        Long tableId = jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store.getId());

        OffsetDateTime ten = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        Reservation later = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, ten.plusHours(2), ten.plusHours(3), 2, ten.minusDays(1)));
        Reservation earlier = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, ten, ten.plusHours(1), 2, ten.minusDays(1)));
        reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, ten.plusDays(1), ten.plusDays(1).plusHours(1), 2, ten.minusDays(1)));

        LocalDate date = ten.toLocalDate();
        assertThat(ownerReservationService.getReservations(owner.getId(), store.getId(), date, null))
                .extracting(OwnerReservationResponse::reservationId)
                .containsExactly(earlier.getId(), later.getId());
        assertThat(ownerReservationService.getReservations(owner.getId(), store.getId(), date, ReservationStatus.CONFIRMED))
                .extracting(OwnerReservationResponse::customerName)
                .containsExactly("test", "test");
        assertThat(ownerReservationService.getReservations(owner.getId(), store.getId(), date, ReservationStatus.NO_SHOW))
                .isEmpty();
        assertThat(ownerReservationService.getReservations(owner.getId() + 999, store.getId(), date, null))
                .isEmpty();

        assertThat(ownerReservationService.getReservation(owner.getId(), earlier.getId()).customerPhone())
                .isEqualTo("010-0000-0000");
        assertThatThrownBy(() -> ownerReservationService.getReservation(owner.getId() + 999, earlier.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static Member member(String email, UserType type) {
        return Member.builder().email(email).password("password").name("test")
                .phone("010-0000-0000").userType(type).build();
    }
}
