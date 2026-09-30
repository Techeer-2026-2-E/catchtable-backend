package com.catchtable.reservation.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreTableRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OwnerReservationFeatureServiceTest {

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private OwnerReservationService ownerReservationService;
    @Autowired private StoreTableRepository storeTableRepository;
    @Autowired private ReservationService reservationService;

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

    @Test
    void ownerCancellationIsIdempotentAndReleasesTheTable() {
        Member owner = member("owner-cancel@example.com", UserType.OWNER);
        Member customer = member("customer-cancel@example.com", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(customer);
        Store store = Store.builder().owner(owner).name("owner cancellation store")
                .category(StoreCategory.KOREAN).address("Seoul")
                .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();
        Long tableId = jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store.getId());
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, startAt, startAt.plusHours(1), 2, startAt.minusDays(1)));

        assertThat(storeTableRepository.countAvailable(store.getId(), 2, startAt, startAt.plusHours(1))).isZero();
        assertThatThrownBy(() -> ownerReservationService.cancelReservation(
                owner.getId() + 999, reservation.getId(), "매장 사정"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        OwnerReservationResponse cancelled = ownerReservationService.cancelReservation(
                owner.getId(), reservation.getId(), "매장 사정");
        OwnerReservationResponse repeated = ownerReservationService.cancelReservation(
                owner.getId(), reservation.getId(), "다른 사유");
        entityManager.flush();

        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.cancellationActor()).isEqualTo(CancellationActor.OWNER);
        assertThat(repeated.cancelledAt()).isEqualTo(cancelled.cancelledAt());
        assertThat(repeated.cancellationReason()).isEqualTo("매장 사정");
        assertThat(storeTableRepository.countAvailable(store.getId(), 2, startAt, startAt.plusHours(1))).isEqualTo(1);
        assertThatThrownBy(() -> ownerReservationService.confirmVisit(owner.getId(), reservation.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void visitConfirmationRecordsActorOnceWithoutChangingReservationStatus() {
        Member owner = member("owner-visit@example.com", UserType.OWNER);
        Member customer = member("customer-visit@example.com", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(customer);
        Store store = Store.builder().owner(owner).name("owner visit store")
                .category(StoreCategory.KOREAN).address("Seoul")
                .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();
        Long tableId = jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store.getId());
        OffsetDateTime startAt = OffsetDateTime.now().minusMinutes(1);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableId, startAt, startAt.plusHours(1), 2, startAt.minusDays(1)));

        OwnerReservationResponse first = ownerReservationService.confirmVisit(owner.getId(), reservation.getId());
        OwnerReservationResponse repeated = ownerReservationService.confirmVisit(owner.getId(), reservation.getId());

        assertThat(first.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(first.checkedInAt()).isNotNull();
        assertThat(first.checkedInByMemberId()).isEqualTo(owner.getId());
        assertThat(repeated.checkedInAt()).isEqualTo(first.checkedInAt());
        assertThatThrownBy(() -> ownerReservationService.cancelReservation(owner.getId(), reservation.getId(), "매장 사정"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void noShowRequiresGracePeriodAndUnvisitedReservation() {
        Member owner = member("owner-no-show@example.com", UserType.OWNER);
        Member customer = member("customer-no-show@example.com", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(customer);
        Store store = Store.builder().owner(owner).name("no-show store")
                .category(StoreCategory.KOREAN).address("Seoul")
                .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();
        List<Long> tableIds = new ArrayList<>();
        for (int tableNumber = 1; tableNumber <= 3; tableNumber++) {
            tableIds.add(jdbcTemplate.queryForObject("""
                    INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                    VALUES (?, ?, 1, 4, 'ACTIVE') RETURNING id
                    """, Long.class, store.getId(), tableNumber));
        }
        OffsetDateTime now = OffsetDateTime.now();
        Reservation late = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableIds.get(0), now.minusHours(2), now.minusHours(1), 2, now.minusDays(1)));
        Reservation early = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableIds.get(1), now.minusMinutes(5), now.plusMinutes(55), 2, now.minusDays(1)));
        Reservation visited = reservationRepository.saveAndFlush(Reservation.confirmed(
                customer, store, tableIds.get(2), now.minusMinutes(30), now.plusMinutes(30), 2, now.minusDays(1)));
        ownerReservationService.confirmVisit(owner.getId(), visited.getId());

        assertThatThrownBy(() -> ownerReservationService.markNoShow(owner.getId(), early.getId(), "미방문"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> ownerReservationService.markNoShow(owner.getId(), visited.getId(), "미방문"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> ownerReservationService.markNoShow(owner.getId() + 999, late.getId(), "미방문"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        OwnerReservationResponse first = ownerReservationService.markNoShow(owner.getId(), late.getId(), "미방문");
        OwnerReservationResponse repeated = ownerReservationService.markNoShow(owner.getId(), late.getId(), "다른 사유");
        assertThat(first.status()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(first.noShowByMemberId()).isEqualTo(owner.getId());
        assertThat(repeated.noShowAt()).isEqualTo(first.noShowAt());
        assertThat(repeated.noShowReason()).isEqualTo("미방문");
        assertThat(reservationService.completeFinishedReservations()).isZero();
        assertThat(reservationRepository.findById(late.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.NO_SHOW);
    }

    private static Member member(String email, UserType type) {
        return Member.builder().email(email).password("password").name("test")
                .phone("010-0000-0000").userType(type).build();
    }
}
