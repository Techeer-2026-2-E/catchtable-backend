package com.catchtable.reservation.entity;

import com.catchtable.member.entity.Member;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.store.entity.Store;
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
        PaymentTestData data = new PaymentTestData(jdbc);
        member = entityManager.getReference(Member.class, data.member("CUSTOMER"));
        store = entityManager.getReference(Store.class, data.store(data.member("OWNER")));
        tableId = jdbc.queryForObject("SELECT id FROM store_table WHERE store_id = ?", Long.class, store.getId());
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
