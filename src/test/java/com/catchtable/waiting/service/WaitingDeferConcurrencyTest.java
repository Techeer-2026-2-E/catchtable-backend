package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.waiting.WaitingDeferTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@WaitingDeferTestContext
class WaitingDeferConcurrencyTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 11);

    @Autowired OwnerWaitingService ownerWaitingService;
    @Autowired WaitingService waitingService;
    @Autowired WaitingQueryService queryService;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(20)
    @DisplayName("연기와 고객 취소가 동시에 와도 순서가 중복·누락 없이 유지된다")
    void deferAndCancel() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Long ownerId = insertMember("owner", "OWNER", suffix);
        Long storeId = insertStore(ownerId, suffix);
        try {
            Long first = insertWaiting(storeId, 1, suffix);
            Long second = insertWaiting(storeId, 2, suffix);
            insertWaiting(storeId, 3, suffix);

            List<Boolean> results = race(
                    () -> ownerWaitingService.deferWaiting(ownerId, first),
                    () -> waitingService.cancelWaiting(memberOf(second), second));

            assertThat(results).containsExactly(true, true);
            assertQueueConsistent(storeId, 1);
        } finally {
            cleanUp(storeId, suffix);
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("연기와 호출이 동시에 와도 순서가 중복·누락 없이 유지된다")
    void deferAndCall() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Long ownerId = insertMember("owner", "OWNER", suffix);
        Long storeId = insertStore(ownerId, suffix);
        try {
            Long first = insertWaiting(storeId, 1, suffix);
            insertWaiting(storeId, 2, suffix);
            insertWaiting(storeId, 3, suffix);

            List<Boolean> results = race(
                    () -> ownerWaitingService.deferWaiting(ownerId, first),
                    () -> ownerWaitingService.callWaiting(ownerId, first));

            // 호출이 먼저면 연기 거부
            assertThat(results.get(1)).isTrue();
            assertQueueConsistent(storeId, results.get(0) ? 1 : 0);
        } finally {
            cleanUp(storeId, suffix);
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 대기열 연기 두 건이 동시에 와도 순서가 중복·누락 없이 유지된다")
    void twoDefers() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Long ownerId = insertMember("owner", "OWNER", suffix);
        Long storeId = insertStore(ownerId, suffix);
        try {
            Long first = insertWaiting(storeId, 1, suffix);
            Long second = insertWaiting(storeId, 2, suffix);
            insertWaiting(storeId, 3, suffix);

            List<Boolean> results = race(
                    () -> ownerWaitingService.deferWaiting(ownerId, first),
                    () -> ownerWaitingService.deferWaiting(ownerId, second));

            assertThat(results).containsExactly(true, true);
            assertQueueConsistent(storeId, 2);
        } finally {
            cleanUp(storeId, suffix);
        }
    }

    // 정렬 순서 = 1..전체 행 수, 진행 중 팀 순번 = 1..N, 연기 횟수 = 이력 수
    private void assertQueueConsistent(Long storeId, int defers) {
        List<Integer> orders = jdbc.queryForList(
                "select queue_order from waiting where store_id = ? order by queue_order", Integer.class, storeId);
        assertThat(orders).isEqualTo(IntStream.rangeClosed(1, orders.size()).boxed().toList());

        List<Long> active = jdbc.queryForList("""
                select id from waiting
                where store_id = ? and status in ('WAITING', 'CALLED', 'CONFIRMED')
                order by queue_order
                """, Long.class, storeId);
        List<Integer> positions = active.stream()
                .map(id -> queryService.getMyWaiting(memberOf(id), id).currentPosition())
                .toList();
        assertThat(positions).isEqualTo(IntStream.rangeClosed(1, active.size()).boxed().toList());

        assertThat(jdbc.queryForObject("select sum(defer_count) from waiting where store_id = ?",
                Integer.class, storeId)).isEqualTo(defers);
        assertThat(jdbc.queryForObject("""
                select count(*) from waiting_defer_history
                where waiting_id in (select id from waiting where store_id = ?)
                """, Integer.class, storeId)).isEqualTo(defers);
    }

    private List<Boolean> race(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return succeeded(first); });
            var b = executor.submit(() -> { start.await(); return succeeded(second); });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private static boolean succeeded(Runnable action) {
        try {
            action.run();
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WAITING_NOT_DEFERRABLE);
            return false;
        }
    }

    private Long memberOf(Long waitingId) {
        return jdbc.queryForObject("select member_id from waiting where id = ?", Long.class, waitingId);
    }

    private Long insertStore(Long ownerId, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, ?, 'KOREAN', 'Seoul', true, 90, 30, 10)
                RETURNING id
                """, Long.class, ownerId, "defer-" + suffix);
    }

    private Long insertWaiting(Long storeId, int number, String suffix) {
        Long customerId = insertMember("customer" + number, "CUSTOMER", suffix);
        return jdbc.queryForObject("""
                INSERT INTO waiting (
                    member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, 2, ?, ?, 'WAITING', now(), now())
                RETURNING id
                """, Long.class, customerId, storeId, TODAY, number, number);
    }

    private Long insertMember(String name, String type, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'test', '010-0000-0000', ?)
                RETURNING id
                """, Long.class, name + "-" + suffix + "@example.com", type);
    }

    private void cleanUp(Long storeId, String suffix) {
        jdbc.update("delete from waiting_defer_history where waiting_id in (select id from waiting where store_id = ?)",
                storeId);
        jdbc.update("delete from waiting_end_history where waiting_id in (select id from waiting where store_id = ?)",
                storeId);
        jdbc.update("delete from waiting where store_id = ?", storeId);
        jdbc.update("delete from store where id = ?", storeId);
        jdbc.update("delete from member where email like ?", "%-" + suffix + "@example.com");
    }
}
