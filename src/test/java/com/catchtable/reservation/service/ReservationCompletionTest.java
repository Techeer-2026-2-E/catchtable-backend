package com.catchtable.reservation.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ReservationCompletionTest {

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationService reservationService;

    @Test
    void completesOnlyVisitedReservationsPastTheirEndOnce() {
        Member owner = member("owner-completion@example.com", UserType.OWNER);
        Member customer = member("customer-completion@example.com", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(customer);
        Store store = Store.builder().owner(owner).name("completion store")
                .category(StoreCategory.KOREAN).address("Seoul")
                .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();

        OffsetDateTime now = OffsetDateTime.now();
        Reservation visitedPast = save(customer, store, 1, now.minusHours(3));
        visitedPast.confirmVisit(now.minusHours(2), owner.getId());
        Reservation unvisitedPast = save(customer, store, 2, now.minusHours(3));
        Reservation visitedFuture = save(customer, store, 3, now.plusHours(2));
        visitedFuture.confirmVisit(now, owner.getId());
        Reservation cancelledPast = save(customer, store, 4, now.minusHours(3));
        cancelledPast.cancelByOwner(now.minusHours(2), "매장 사정");
        entityManager.flush();

        assertThat(reservationService.completeFinishedReservations()).isEqualTo(1);
        assertThat(reservationService.completeFinishedReservations()).isZero();

        Reservation completed = reservationRepository.findById(visitedPast.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(reservationRepository.findById(unvisitedPast.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservationRepository.findById(visitedFuture.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservationRepository.findById(cancelledPast.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    private Reservation save(Member customer, Store store, int tableNumber, OffsetDateTime startAt) {
        Long tableId = jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, ?, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store.getId(), tableNumber);
        return reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, startAt, startAt.plusHours(1), 2, startAt.minusDays(1)));
    }

    private static Member member(String email, UserType type) {
        return Member.builder().email(email).password("password").name("test")
                .phone("010-0000-0000").userType(type).build();
    }
}
