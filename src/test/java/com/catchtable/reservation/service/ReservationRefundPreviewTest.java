package com.catchtable.reservation.service;

import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.client.TossPaymentsClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ReservationRefundPreviewTest.FixedClock.class)
class ReservationRefundPreviewTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T00:00:00+09:00");
    private static final OffsetDateTime START = NOW.plusHours(18);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ReservationService service;
    @Autowired private MockMvc mvc;
    @MockBean private TossPaymentsClient toss;

    private PaymentTestData data;
    private Long memberId;
    private Long storeId;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        storeId = data.store(data.member("OWNER"));
    }

    @Test
    void usesOriginalAmountAndPolicyWithTheCurrentReservationDate() throws Exception {
        Long reservationId = reservation(10_001L, "FLEXIBLE", true);
        Long paymentId = data.payment(memberId, reservationId, "PARTIAL_REFUNDED", 1_000);
        jdbc.update("UPDATE payment SET amount = 10001 WHERE id = ?", paymentId);

        mvc.perform(get(path(reservationId)).header("X-Member-Id", memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.paidAmount").value(10_001))
                .andExpect(jsonPath("$.alreadyRefundedAmount").value(1_000))
                .andExpect(jsonPath("$.policyType").value("FLEXIBLE"))
                .andExpect(jsonPath("$.refundRate").value(50))
                .andExpect(jsonPath("$.refundableAmount").value(4_000))
                .andExpect(jsonPath("$.nonRefundableAmount").value(5_001));

        jdbc.update("UPDATE reservation SET reservation_start_at = ?, reservation_end_at = ? WHERE id = ?",
                START.plusDays(1), START.plusDays(1).plusHours(1), reservationId);
        entityManager.clear();
        var changed = service.getRefundPreview(memberId, reservationId);
        assertThat(changed.paidAmount()).isEqualTo(10_001);
        assertThat(changed.policyType().name()).isEqualTo("FLEXIBLE");
        assertThat(changed.refundRate()).isEqualTo(100);
        assertThat(changed.refundableAmount()).isEqualTo(9_001);
        verifyNoInteractions(toss);
    }

    @ParameterizedTest
    @CsvSource({"FLEXIBLE,0,50", "STANDARD,0,0", "STANDARD,1,50", "STRICT,1,20", "STRICT,2,50", "STRICT,3,100"})
    void usesSeoulCalendarDatesAndTheStoredPolicy(String policy, int daysBefore, int rate) {
        Long reservationId = reservation(10_000L, policy, true);
        data.payment(memberId, reservationId, "DONE", 0);
        jdbc.update("UPDATE reservation SET reservation_start_at = ?, reservation_end_at = ? WHERE id = ?",
                START.plusDays(daysBefore), START.plusDays(daysBefore).plusHours(1), reservationId);

        var preview = service.getRefundPreview(memberId, reservationId);

        assertThat(preview.refundRate()).isEqualTo(rate);
        assertThat(preview.refundableAmount()).isEqualTo(100L * rate);
        assertThat(preview.calculatedAt().toInstant()).isEqualTo(NOW.toInstant());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothLegacyAndNewFreeReservationsReturnZero(boolean snapshotRecorded) throws Exception {
        Long reservationId = reservation(snapshotRecorded ? 0L : null, null, false);

        mvc.perform(get(path(reservationId)).header("X-Member-Id", memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(0))
                .andExpect(jsonPath("$.paymentId").doesNotExist())
                .andExpect(jsonPath("$.policyType").doesNotExist())
                .andExpect(jsonPath("$.refundableAmount").value(0));
        verifyNoInteractions(toss);
    }

    @Test
    void anUnknownHoldIsNotAssumedFreeWhenItsPaymentIsMissing() throws Exception {
        assertDataNotReady(reservation(null, null, true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void paymentEvidenceWithoutOriginalTermsIsNeverTreatedAsFree(boolean held) throws Exception {
        Long reservationId = reservation(null, null, held);
        data.payment(memberId, reservationId, "DONE", 0);
        assertDataNotReady(reservationId);
    }

    @Test
    void paidTermsWithoutAnApprovedPaymentAreNotReady() throws Exception {
        assertDataNotReady(reservation(10_000L, "FLEXIBLE", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "TIMEOUT", "FAILED", "CANCELED"})
    void unapprovedPaymentsDoNotSupplyPaidAmount(String paymentStatus) throws Exception {
        Long reservationId = reservation(10_000L, "FLEXIBLE", true);
        data.payment(memberId, reservationId, paymentStatus, 0);
        assertDataNotReady(reservationId);
    }

    @Test
    void mismatchingPaymentAmountOrMemberIsNotAccepted() throws Exception {
        Long reservationId = reservation(20_000L, "FLEXIBLE", true);
        Long paymentId = data.payment(memberId, reservationId, "DONE", 0);
        assertDataNotReady(reservationId);
        jdbc.update("UPDATE payment SET amount = 20000, member_id = ? WHERE id = ?",
                data.member("CUSTOMER"), paymentId);
        entityManager.clear();
        assertDataNotReady(reservationId);
    }

    @Test
    void multipleApprovedAttemptsAreNotSilentlySummedOrSelected() throws Exception {
        Long reservationId = reservation(10_000L, "FLEXIBLE", true);
        data.payment(memberId, reservationId, "REFUNDED", 10_000);
        data.payment(memberId, reservationId, "DONE", 0);
        assertDataNotReady(reservationId);
    }

    @ParameterizedTest
    @ValueSource(longs = {6_000, 10_000})
    void priorRefundsClampAdditionalAmountToZeroWithoutAnyWrite(long refundedAmount) {
        Long reservationId = reservation(10_000L, "FLEXIBLE", true);
        String paymentStatus = refundedAmount == 10_000 ? "REFUNDED" : "PARTIAL_REFUNDED";
        Long paymentId = data.payment(memberId, reservationId, paymentStatus, refundedAmount);
        var first = service.getRefundPreview(memberId, reservationId);
        var second = service.getRefundPreview(memberId, reservationId);

        assertThat(first).isEqualTo(second);
        assertThat(first.refundableAmount()).isZero();
        assertThat(first.nonRefundableAmount()).isEqualTo(10_000 - refundedAmount);
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, paymentId))
                .isEqualTo(refundedAmount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id = ?",
                Long.class, paymentId)).isZero();
        verifyNoInteractions(toss);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "EXPIRED", "CANCELLED", "COMPLETED", "NO_SHOW"})
    void onlyConfirmedReservationsCanBePreviewed(String reservationStatus) throws Exception {
        Long reservationId = reservation(10_000L, "FLEXIBLE", true);
        jdbc.update("""
                UPDATE reservation SET status = ?,
                    confirmed_at = CASE WHEN ? IN ('PENDING_PAYMENT', 'EXPIRED') THEN NULL ELSE confirmed_at END
                WHERE id = ?
                """, reservationStatus, reservationStatus, reservationId);
        assertNotCancellable(reservationId);
    }

    @Test
    void checkedInReservationIsRejected() throws Exception {
        Long reservationId = reservation(0L, null, false);
        jdbc.update("UPDATE reservation SET checked_in_at = ? WHERE id = ?", NOW, reservationId);
        assertNotCancellable(reservationId);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void atOrAfterTheStartIsRejectedEvenForFreeReservations(int secondsAfterNow) throws Exception {
        Long reservationId = reservation(0L, null, false);
        jdbc.update("UPDATE reservation SET reservation_start_at = ? WHERE id = ?",
                NOW.plusSeconds(secondsAfterNow), reservationId);
        assertNotCancellable(reservationId);
    }

    @Test
    void ownershipAndHeaderErrorsKeepTheExistingHttpContract() throws Exception {
        Long reservationId = reservation(0L, null, false);
        mvc.perform(get(path(reservationId))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(reservationId)).header("X-Member-Id", "not-a-number"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path(reservationId)).header("X-Member-Id", data.member("CUSTOMER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(get(path(Long.MAX_VALUE)).header("X-Member-Id", memberId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    private Long reservation(Long amount, String policy, boolean held) {
        Long id = data.hold(memberId, storeId, 0);
        jdbc.update("""
                UPDATE reservation SET status = 'CONFIRMED', confirmed_at = ?,
                    hold_expires_at = CASE WHEN ? THEN hold_expires_at ELSE NULL END,
                    deposit_amount_snapshot = ?, refund_policy_snapshot = ? WHERE id = ?
                """, NOW.minusDays(1), held, amount, policy, id);
        return id;
    }

    private void assertDataNotReady(Long id) throws Exception {
        mvc.perform(get(path(id)).header("X-Member-Id", memberId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_DATA_NOT_READY"));
    }

    private void assertNotCancellable(Long id) throws Exception {
        mvc.perform(get(path(id)).header("X-Member-Id", memberId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
    }

    private String path(Long id) {
        return "/api/user/reservations/" + id + "/refund-preview";
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }
}
