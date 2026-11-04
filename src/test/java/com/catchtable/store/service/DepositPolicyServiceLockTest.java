package com.catchtable.store.service;

import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.reservation.entity.RefundPolicy;
import com.catchtable.store.dto.DepositPolicyRequest;
import com.catchtable.store.dto.ReservationPolicyRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"reservation.hold-expiry-interval-ms=3600000", "reservation.completion-interval-ms=3600000"})
@Import(FakeTossPaymentsClient.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DepositPolicyServiceLockTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired DepositPolicyService depositPolicyService;
    @Autowired ReservationPolicyService reservationPolicyService;
    PaymentTestData data;
    Long ownerId, storeId;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        ownerId = data.member("OWNER");
        storeId = data.store(ownerId);
    }

    @AfterEach
    void cleanUp() {
        data.deleteStore(storeId, List.of(ownerId));
    }

    @Test
    @Timeout(20)
    void replacePolicyWaitsForStoreLockAndKeepsCommittedChangesOfOtherColumns() throws Exception {
        CountDownLatch updated = new CountDownLatch(1), commit = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var otherWriter = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.update("UPDATE store SET reservation_duration_minutes = 90 WHERE id = ?", storeId);
                updated.countDown();
                awaitLatch(commit);
            }));
            assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
            var replacing = workers.submit(() -> depositPolicyService.replacePolicy(
                    storeId, new DepositPolicyRequest(true, 10_000L, RefundPolicy.STRICT)));
            try {
                awaitStoreWaiter();
                assertThat(replacing.isDone()).isFalse();
            } finally {
                commit.countDown();
            }
            otherWriter.get(10, TimeUnit.SECONDS);
            replacing.get(10, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
        }
        var row = jdbc.queryForMap("""
                SELECT reservation_duration_minutes, deposit_enabled, deposit_amount, refund_policy
                FROM store WHERE id = ?
                """, storeId);
        assertThat(row).containsEntry("reservation_duration_minutes", 90)
                .containsEntry("deposit_enabled", true)
                .containsEntry("deposit_amount", 10_000L)
                .containsEntry("refund_policy", "STRICT");
    }

    // 먼저 읽은 다른 저장이 나중에 커밋해도, 그사이 저장된 예약금 값을 되돌리면 안 된다.
    @Test
    @Timeout(20)
    void laterCommitOfReservationPolicyKeepsDepositPolicySavedInBetween() {
        try (var worker = Executors.newSingleThreadExecutor()) {
            transactions.executeWithoutResult(tx -> {
                reservationPolicyService.replacePolicy(storeId, new ReservationPolicyRequest(90, 30, 10, 30, 0));
                try {
                    worker.submit(() -> depositPolicyService.replacePolicy(
                            storeId, new DepositPolicyRequest(true, 10_000L, RefundPolicy.STRICT)))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        var row = jdbc.queryForMap("""
                SELECT reservation_duration_minutes, deposit_enabled, deposit_amount, refund_policy
                FROM store WHERE id = ?
                """, storeId);
        assertThat(row).containsEntry("reservation_duration_minutes", 90)
                .containsEntry("deposit_enabled", true)
                .containsEntry("deposit_amount", 10_000L)
                .containsEntry("refund_policy", "STRICT");
    }

    private void awaitStoreWaiter() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                AND wait_event_type = 'Lock' AND query ILIKE '%store%'
                """, Integer.class)).isPositive());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("test synchronization timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
