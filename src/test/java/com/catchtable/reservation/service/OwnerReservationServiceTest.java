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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class OwnerReservationServiceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private OwnerReservationService ownerReservationService;

    @Autowired
    private ReservationService reservationService;

    @Test
    void ownerCanManageOnlyOwnStoreReservationsIdempotently() {
        Member owner = member("owner-api@example.com", "owner", "010-0000-0021", UserType.OWNER);
        Member anotherOwner = member(
                "another-owner@example.com", "another owner", "010-0000-0022", UserType.OWNER);
        Member customer = member("owner-customer@example.com", "customer", "010-0000-0023", UserType.CUSTOMER);
        entityManager.persist(owner);
        entityManager.persist(anotherOwner);
        entityManager.persist(customer);

        Store store = Store.builder()
                .owner(owner)
                .name("owner store")
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
                VALUES (?, 3, 4, 'ACTIVE')
                RETURNING id
                """, Long.class, store.getId());

        ZoneOffset storeOffset = ZoneOffset.ofHours(9);
        OffsetDateTime futureStart = OffsetDateTime.now(storeOffset)
                .plusYears(10)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        Reservation visitReservation = saveReservation(customer, store, tableId, futureStart);
        Reservation cancelReservation = saveReservation(customer, store, tableId, futureStart.plusHours(2));
        Reservation noShowReservation = saveReservation(
                customer,
                store,
                tableId,
                OffsetDateTime.now(storeOffset).minusYears(1)
        );
        Reservation completionReservation = saveReservation(
                customer,
                store,
                tableId,
                OffsetDateTime.now(storeOffset).minusYears(2)
        );

        assertThat(ownerReservationService.getReservations(
                owner.getId(), store.getId(), futureStart.toLocalDate(), ReservationStatus.CONFIRMED))
                .extracting(OwnerReservationResponse::reservationId)
                .containsExactly(visitReservation.getId(), cancelReservation.getId());
        assertThat(ownerReservationService.getReservations(
                anotherOwner.getId(), store.getId(), futureStart.toLocalDate(), null))
                .isEmpty();
        assertThatThrownBy(() -> ownerReservationService.getReservation(
                anotherOwner.getId(), visitReservation.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        OwnerReservationResponse firstVisit = ownerReservationService.confirmVisit(
                owner.getId(), visitReservation.getId());
        OwnerReservationResponse repeatedVisit = ownerReservationService.confirmVisit(
                owner.getId(), visitReservation.getId());
        assertThat(firstVisit.checkedInByMemberId()).isEqualTo(owner.getId());
        assertThat(repeatedVisit.checkedInAt()).isEqualTo(firstVisit.checkedInAt());

        ownerReservationService.confirmVisit(owner.getId(), completionReservation.getId());
        assertThat(reservationService.completeFinishedReservations()).isEqualTo(1);
        entityManager.clear();
        Reservation completed = reservationRepository.findById(completionReservation.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();

        OwnerReservationResponse firstNoShow = ownerReservationService.markNoShow(
                owner.getId(), noShowReservation.getId(), "did not arrive");
        OwnerReservationResponse repeatedNoShow = ownerReservationService.markNoShow(
                owner.getId(), noShowReservation.getId(), "different reason");
        assertThat(firstNoShow.status()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(repeatedNoShow.noShowAt()).isEqualTo(firstNoShow.noShowAt());
        assertThat(repeatedNoShow.noShowReason()).isEqualTo("did not arrive");

        OwnerReservationResponse cancelled = ownerReservationService.cancelReservation(
                owner.getId(), cancelReservation.getId(), "store issue");
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.cancellationActor()).isEqualTo(CancellationActor.OWNER);

        assertThatThrownBy(() -> ownerReservationService.markNoShow(
                owner.getId(), visitReservation.getId(), "late"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private Member member(String email, String name, String phone, UserType userType) {
        return Member.builder()
                .email(email)
                .password("password")
                .name(name)
                .phone(phone)
                .userType(userType)
                .build();
    }

    private Reservation saveReservation(
            Member customer,
            Store store,
            Long tableId,
            OffsetDateTime start
    ) {
        return reservationRepository.saveAndFlush(Reservation.confirmed(
                customer,
                store,
                tableId,
                start,
                start.plusHours(2),
                2,
                start.minusDays(1)
        ));
    }
}
