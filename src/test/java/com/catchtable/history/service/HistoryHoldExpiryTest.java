package com.catchtable.history.service;

import com.catchtable.history.dto.HistoryItemResponse;
import com.catchtable.history.dto.HistoryStatus;
import com.catchtable.history.dto.HistoryType;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.OwnerReservationService;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Import(HistoryHoldExpiryTest.FixedClock.class)
@Transactional
class HistoryHoldExpiryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-10T12:00:00+09:00");
    private static final OffsetDateTime START = NOW.plusDays(1);

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoryService histories;
    @Autowired private ReservationService reservations;
    @Autowired private OwnerReservationService ownerReservations;

    private Member owner;
    private Member customer;
    private Store store;
    private Long tableId;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW.toInstant(), NOW.getOffset());
        }
    }

    @BeforeEach
    void setUp() {
        owner = Member.builder().name("owner").phone("010-0000-0001").userType(UserType.OWNER).build();
        customer = Member.builder().name("customer").phone("010-0000-0002").userType(UserType.CUSTOMER).build();
        entityManager.persist(owner);
        entityManager.persist(customer);
        store = Store.builder().owner(owner).name("hold history store").category(StoreCategory.KOREAN)
                .address("Seoul").reservationDurationMinutes(60).reservationSlotMinutes(30)
                .arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();
        tableId = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store.getId());
    }

    @ParameterizedTest
    @CsvSource({"-1, PENDING_PAYMENT, true", "0, EXPIRED, false", "1, EXPIRED, false"})
    void allReadsUseTheHoldDeadlineBeforeScheduledCleanup(
            int secondsSinceExpiry, ReservationStatus expectedStatus, boolean active) {
        Reservation hold = Reservation.held(customer, store, tableId, START, START.plusHours(1), 2,
                NOW.minusMinutes(5).minusSeconds(secondsSinceExpiry));
        entityManager.persist(hold);
        entityManager.flush();
        entityManager.clear();

        for (HistoryType type : new HistoryType[]{null, HistoryType.RESERVATION}) {
            assertThat(histories.getHistories(customer.getId(), type, null))
                    .extracting(HistoryItemResponse::id, HistoryItemResponse::status, HistoryItemResponse::active)
                    .containsExactly(tuple(hold.getId(), expectedStatus.name(), active));
            assertThat(histories.getHistories(customer.getId(), type, active ? HistoryStatus.ACTIVE : HistoryStatus.ENDED))
                    .extracting(HistoryItemResponse::id).containsExactly(hold.getId());
            assertThat(histories.getHistories(customer.getId(), type, active ? HistoryStatus.ENDED : HistoryStatus.ACTIVE))
                    .isEmpty();
        }
        assertThat(reservations.getReservation(customer.getId(), hold.getId()).status()).isEqualTo(expectedStatus);
        assertThat(ownerReservations.getReservation(owner.getId(), hold.getId()).status()).isEqualTo(expectedStatus);
        assertThat(ownerReservations.getReservations(owner.getId(), store.getId(), START.toLocalDate(), null))
                .extracting(OwnerReservationResponse::reservationId, OwnerReservationResponse::status)
                .containsExactly(tuple(hold.getId(), expectedStatus));
        assertThat(ownerReservations.getReservations(owner.getId(), store.getId(), START.toLocalDate(), expectedStatus))
                .extracting(OwnerReservationResponse::reservationId).containsExactly(hold.getId());
        ReservationStatus otherStatus = active ? ReservationStatus.EXPIRED : ReservationStatus.PENDING_PAYMENT;
        assertThat(ownerReservations.getReservations(owner.getId(), store.getId(), START.toLocalDate(), otherStatus))
                .isEmpty();
        assertThat(ownerReservations.getReservations(owner.getId() + 999, store.getId(), START.toLocalDate(), ReservationStatus.EXPIRED))
                .isEmpty();
        assertThat(ownerReservations.getReservations(owner.getId(), store.getId(), START.toLocalDate().plusDays(1), ReservationStatus.EXPIRED))
                .isEmpty();
        assertThat(histories.getHistories(customer.getId() + 999, HistoryType.RESERVATION, HistoryStatus.ENDED)).isEmpty();

        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, hold.getId()))
                .as("read-only responses do not depend on a stored status update").isEqualTo("PENDING_PAYMENT");
        assertThat(reservations.getHold(customer.getId(), hold.getId()).status()).isEqualTo(expectedStatus);
    }
}
