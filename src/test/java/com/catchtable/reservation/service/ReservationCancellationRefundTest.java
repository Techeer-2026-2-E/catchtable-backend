package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.store.repository.StoreTableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 커밋·롤백과 AFTER_COMMIT 알림을 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReservationCancellationRefundTest.FixedClock.class)
class ReservationCancellationRefundTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    private static final OffsetDateTime START = NOW.plusHours(6);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationService customers;
    @Autowired private OwnerReservationService owners;
    @Autowired private StoreTableRepository tables;
    @Autowired private SseEmitterRepository emitters;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @MockBean private TossPaymentsClient toss;

    private PaymentTestData data;
    private Long memberId;
    private Long ownerId;
    private Long storeId;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        ownerId = data.member("OWNER");
        storeId = data.store(ownerId);
        emitter = mock(SseEmitter.class);
        emitters.save(memberId, emitter);
    }

    @AfterEach
    void cleanUp() {
        emitters.delete(memberId, emitter);
        jdbc.update("DELETE FROM payment_refund WHERE payment_id IN (SELECT id FROM payment WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?))", storeId);
        jdbc.update("DELETE FROM payment_status_history WHERE payment_id IN (SELECT id FROM payment WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?))", storeId);
        jdbc.update("DELETE FROM payment WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store_table WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, ownerId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "UNKNOWN"})
    void anInFlightRefundBlocksEvenAZeroEntitlementCancellation(String refundStatus) {
        Long id = reservation(10_000L, "STRICT", true);
        Long paymentId = data.payment(memberId, id, "DONE", 0);
        jdbc.update("""
                INSERT INTO payment_refund
                    (payment_id, idempotency_key, cancel_reason, refund_amount, status, requested_at)
                VALUES (?, 'cancel-pending', '환불 요청', 5000, ?, ?)
                """, paymentId, refundStatus, NOW);

        for (boolean owner : new boolean[] {false, true}) {
            assertThatThrownBy(() -> cancel(owner, id)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFUND_DATA_NOT_READY));
            assertPreserved(id);
        }
        verifyNoInteractions(toss);
    }

    @ParameterizedTest
    @CsvSource({
            "false,FLEXIBLE,DONE,0,false",
            "false,FLEXIBLE,PARTIAL_REFUNDED,4000,false",
            "false,FLEXIBLE,PARTIAL_REFUNDED,5000,true",
            "false,FLEXIBLE,REFUNDED,10000,true",
            "false,STRICT,DONE,0,true",
            "true,STRICT,DONE,0,false",
            "true,STRICT,PARTIAL_REFUNDED,5000,false",
            "true,STRICT,REFUNDED,10000,true"
    })
    void cancellationRequiresTheActorsRefundToBeComplete(boolean owner, String policy, String paymentStatus,
                                                        long refunded, boolean allowed) throws Exception {
        Long id = reservation(10_000L, policy, true);
        Long paymentId = data.payment(memberId, id, paymentStatus, refunded);

        if (allowed) {
            cancel(owner, id);
            cancel(owner, id);
            assertThat(state(id)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT cancelled_at FROM reservation WHERE id = ?",
                    OffsetDateTime.class, id).toInstant()).isEqualTo(NOW.toInstant());
            assertThat(jdbc.queryForObject("SELECT cancellation_actor FROM reservation WHERE id = ?", String.class, id))
                    .isEqualTo(owner ? "OWNER" : "CUSTOMER");
            assertThat(tables.countAvailable(storeId, 2, START, START.plusHours(1), NOW)).isEqualTo(1);
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        } else {
            assertThatThrownBy(() -> cancel(owner, id)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFUND_NOT_COMPLETED));
            assertPreserved(id);
        }
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, paymentId))
                .isEqualTo(refunded);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
                .isEqualTo(paymentStatus);
        verifyNoInteractions(toss);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void freeCancellationAndItsRetryRemainAvailable(boolean owner, boolean legacy) throws Exception {
        Long id = reservation(legacy ? null : 0L, null, false);
        cancel(owner, id);
        cancel(owner, id);
        assertThat(state(id)).isEqualTo("CANCELLED");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verifyNoInteractions(toss);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "TIMEOUT", "FAILED", "CANCELED", "MISSING"})
    void unconfirmedPaymentDataRollsBackBothCustomerAndOwnerCancellation(String paymentStatus) {
        Long id = reservation(10_000L, "FLEXIBLE", true);
        if (!paymentStatus.equals("MISSING")) data.payment(memberId, id, paymentStatus, 0);
        for (boolean owner : new boolean[] {false, true}) {
            assertThatThrownBy(() -> cancel(owner, id)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFUND_DATA_NOT_READY));
            assertPreserved(id);
        }
        verifyNoInteractions(toss);
    }

    @Test
    void missingPaidSnapshotIsNotAFreeCancellation() throws Exception {
        Long id = reservation(null, null, true);
        data.payment(memberId, id, "DONE", 0);
        mvc.perform(post("/api/user/reservations/{id}/cancel", id).header("X-Member-Id", memberId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_DATA_NOT_READY"));
        assertPreserved(id);
    }

    @Test
    void positiveRefundHasAStableHttpErrorAndDoesNotReleaseCapacity() throws Exception {
        Long id = reservation(10_000L, "FLEXIBLE", true);
        data.payment(memberId, id, "DONE", 0);
        mvc.perform(post("/api/user/reservations/{id}/cancel", id).header("X-Member-Id", memberId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_NOT_COMPLETED"));
        assertPreserved(id);
    }

    @Test
    void ownerAfterTheReservationDateStillRequiresTheFullOriginalAmount() {
        Long id = reservation(10_000L, "STRICT", true);
        data.payment(memberId, id, "PARTIAL_REFUNDED", 5_000);
        jdbc.update("UPDATE reservation SET reservation_start_at = ?, reservation_end_at = ? WHERE id = ?",
                START.minusDays(2), START.minusDays(2).plusHours(1), id);
        assertThatThrownBy(() -> cancel(true, id)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFUND_NOT_COMPLETED));
        assertThat(state(id)).isEqualTo("CONFIRMED");
        verifyNoInteractions(emitter, toss);
    }

    @Test
    @Timeout(20)
    void cancellationWaitsForThePaymentLockAndUsesTheCommittedRefund() throws Exception {
        Long id = reservation(10_000L, "FLEXIBLE", true);
        Long paymentId = data.payment(memberId, id, "DONE", 0);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM payment WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, paymentId);
                lock.executeQuery().close();
            }
            var cancellation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                started.countDown();
                customers.cancelReservation(memberId, id);
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')
                        """, Boolean.class, pid.get()));
                if (!waiting) Thread.sleep(10);
            }
            assertThat(waiting).isTrue();
            assertThat(state(id)).isEqualTo("CONFIRMED");
            try (var refund = connection.prepareStatement("UPDATE payment SET refunded_amount = amount, status = 'REFUNDED' WHERE id = ?")) {
                refund.setLong(1, paymentId);
                refund.executeUpdate();
            }
            connection.commit();
            cancellation.get(5, TimeUnit.SECONDS);
            assertThat(state(id)).isEqualTo("CANCELLED");
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verifyNoInteractions(toss);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
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

    private void cancel(boolean owner, Long id) {
        if (owner) owners.cancelReservation(ownerId, id, "매장 사정");
        else customers.cancelReservation(memberId, id);
    }

    private String state(Long id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private void assertPreserved(Long id) {
        assertThat(state(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM reservation WHERE id = ?", OffsetDateTime.class, id))
                .isNull();
        assertThat(tables.countAvailable(storeId, 2, START, START.plusHours(1), NOW)).isZero();
        verifyNoInteractions(emitter);
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
