package com.catchtable.reservation.entity;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ReservationDepositSnapshotTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-19T12:00:00+09:00");
    private static final OffsetDateTime START = NOW.plusDays(1);

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private Member member;
    private Store store;
    private Long tableId;

    @BeforeEach
    void setUp() {
        Member owner = member(UserType.OWNER);
        member = member(UserType.CUSTOMER);
        store = Store.builder().owner(owner).name("snapshot store").category(StoreCategory.KOREAN)
                .address("Seoul").reservationDurationMinutes(60).reservationSlotMinutes(30)
                .arrivalGraceMinutes(10).build();
        entityManager.persist(store);
        entityManager.flush();
        tableId = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store.getId());
    }

    private Member member(UserType type) {
        Member result = Member.builder().email("snapshot-" + UUID.randomUUID() + "@example.com")
                .password("password").name("test").phone("010-0000-0000").userType(type).build();
        entityManager.persist(result);
        return result;
    }

    @Test
    void paidHoldPersistsOriginalTermsWithoutMultiplyingPartySize() {
        Reservation hold = Reservation.held(member, store, tableId, START, START.plusHours(1),
                4, NOW, 10_001, RefundPolicy.STRICT);
        entityManager.persist(hold);
        entityManager.flush();
        entityManager.clear();

        Reservation saved = entityManager.find(Reservation.class, hold.getId());
        assertThat(saved.getDepositAmountSnapshot()).isEqualTo(10_001);
        assertThat(saved.getRefundPolicySnapshot()).isEqualTo(RefundPolicy.STRICT);
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(saved.getConfirmedAt()).isNull();
        assertThat(saved.getHoldExpiresAt()).isEqualTo(NOW.plusMinutes(5));

        saved.releaseHold(NOW.plusMinutes(1));
        entityManager.flush();
        entityManager.clear();
        Reservation released = entityManager.find(Reservation.class, hold.getId());
        assertThat(released.getDepositAmountSnapshot()).isEqualTo(10_001);
        assertThat(released.getRefundPolicySnapshot()).isEqualTo(RefundPolicy.STRICT);
    }

    @Test
    void freeConfirmationRecordsZeroWithoutInventingARefundPolicy() {
        Reservation reservation = Reservation.confirmed(member, store, tableId, START, START.plusHours(1), 2, NOW);
        entityManager.persist(reservation);
        entityManager.flush();
        entityManager.clear();

        Reservation saved = entityManager.find(Reservation.class, reservation.getId());
        assertThat(saved.getDepositAmountSnapshot()).isZero();
        assertThat(saved.getRefundPolicySnapshot()).isNull();
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void holdWithoutTheStoreContractKeepsUnknownTerms() {
        Reservation hold = Reservation.held(member, store, tableId, START, START.plusHours(1), 2, NOW);
        entityManager.persist(hold);
        entityManager.flush();
        entityManager.clear();

        Reservation saved = entityManager.find(Reservation.class, hold.getId());
        assertThat(saved.getDepositAmountSnapshot()).isNull();
        assertThat(saved.getRefundPolicySnapshot()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void paidHoldRejectsNonPositiveAmount(long amount) {
        assertThatThrownBy(() -> Reservation.held(member, store, tableId, START, START.plusHours(1),
                2, NOW, amount, RefundPolicy.FLEXIBLE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paidHoldRequiresTheOriginalPolicy() {
        assertThatThrownBy(() -> Reservation.held(member, store, tableId, START, START.plusHours(1),
                2, NOW, 10_000, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1,FLEXIBLE", "0,FLEXIBLE", "10000,", ",FLEXIBLE", "10000,UNKNOWN"})
    void databaseRejectsIncompleteOrInvalidTerms(Long amount, String policy) {
        Reservation hold = Reservation.held(member, store, tableId, START, START.plusHours(1), 2, NOW);
        entityManager.persist(hold);
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE reservation SET deposit_amount_snapshot = ?, refund_policy_snapshot = ? WHERE id = ?
                """, amount, policy, hold.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
