package com.catchtable.payment.service;

import com.catchtable.payment.ReconcileTestClock;

import com.catchtable.payment.ConfirmedEventCounter;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.reservation.service.ReservationPaymentService;
import com.catchtable.reservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// 트랜잭션 없음 (요청마다 실제 커밋), 고정 Clock, UUID 데이터는 매 테스트 후 정리
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import({FakeTossPaymentsClient.Config.class, ConfirmedEventCounter.Config.class, ReconcileTestClock.Config.class})
class PaymentReconcileConcurrencyTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");

    @Autowired PaymentReconcileService service;
    @Autowired ReservationPaymentService reservationPaymentService;
    @Autowired ReservationService reservationService;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired ConfirmedEventCounter events;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReconcileTestClock clock;

    private PaymentTestData data;
    private Long customerId;
    private Long ownerId;
    private Long storeId;
    private Long reservationId;

    @BeforeEach
    void setUp() {
        clock.set(NOW);


        toss.reset();
        data = new PaymentTestData(jdbc);
        customerId = data.member("CUSTOMER");
        ownerId = data.member("OWNER");
        storeId = data.store(ownerId);
        reservationId = data.hold(customerId, storeId, 0);
    }

    @AfterEach
    void cleanUp() {
        data.deleteStore(storeId, List.of(customerId, ownerId));
    }

    @Test
    @Timeout(20)
    @DisplayName("사용자 승인과 재확인이 동시에 와도 예약 확정·이력·알림은 1회다")
    void confirmAndReconcileConfirmOnce() throws Exception {
        jdbc.update("""
                UPDATE reservation SET requested_at = ?, hold_expires_at = ?,
                    deposit_amount_snapshot = ?, refund_policy_snapshot = 'FLEXIBLE' WHERE id = ?
                """, NOW.minusMinutes(1), NOW.plusMinutes(4), AMOUNT, reservationId);
        Long paymentId = data.payment(customerId, reservationId, "READY", 0);
        String orderId = data.orderId(paymentId);
        toss.delay(200);

        List<Object> outcomes = concurrently(
                () -> reservationPaymentService.confirm(customerId, new PaymentConfirmRequest(orderId, "pk-1", AMOUNT)),
                () -> {
                    Thread.sleep(100);  // 승인 시작 기록 뒤
                    service.reconcile(paymentId);
                    return null;
                });
        service.reconcile(paymentId);

        assertThat(outcomes).filteredOn(Throwable.class::isInstance).isEmpty();
        assertThat(status("payment", paymentId)).isEqualTo("DONE");
        assertThat(status("reservation", reservationId)).isEqualTo("CONFIRMED");
        assertThat(histories(paymentId)).isEqualTo(1);
        assertThat(events.count(reservationId)).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    @DisplayName("재확인과 선점 만료가 동시에 와도 승인 보호된 선점은 만료되지 않는다")
    void reconcileAndExpiryKeepProtectedHold() throws Exception {
        Long paymentId = data.payment(customerId, reservationId, "TIMEOUT", 0);
        data.protect(reservationId, paymentId, "pk-1", NOW.minusMinutes(1));
        toss.query(data.orderId(paymentId), null, "IN_PROGRESS", AMOUNT);
        toss.delay(200);
        clock.set(NOW.plusMinutes(30));

        List<Object> outcomes = concurrently(() -> {
            service.reconcile(paymentId);
            return null;
        }, reservationService::expireHolds);

        assertThat(outcomes).filteredOn(Throwable.class::isInstance).isEmpty();
        assertThat(status("reservation", reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(status("payment", paymentId)).isEqualTo("TIMEOUT");
    }

    @Test
    @Timeout(20)
    @DisplayName("재확인 두 개가 동시에 와도 상태 이력·알림은 1건이다")
    void concurrentReconcilesRecordOnce() throws Exception {
        Long paymentId = data.payment(customerId, reservationId, "TIMEOUT", 0);
        data.protect(reservationId, paymentId, "pk-1", NOW.minusMinutes(1));
        toss.query(data.orderId(paymentId), "pk-1", "DONE", AMOUNT);
        toss.delay(200);

        List<Object> outcomes = concurrently(() -> {
            service.reconcile(paymentId);
            return null;
        }, () -> {
            service.reconcile(paymentId);
            return null;
        });

        assertThat(outcomes).filteredOn(Throwable.class::isInstance).isEmpty();
        assertThat(status("reservation", reservationId)).isEqualTo("CONFIRMED");
        assertThat(histories(paymentId)).isEqualTo(1);
        assertThat(events.count(reservationId)).isEqualTo(1);
    }

    // 동시에 시작, 결과 또는 예외
    @SafeVarargs
    private static List<Object> concurrently(Callable<Object>... tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private String status(String table, Long id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private long histories(Long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id = ?", Long.class,
                paymentId);
    }
}
