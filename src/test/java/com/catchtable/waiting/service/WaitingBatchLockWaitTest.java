package com.catchtable.waiting.service;

import com.catchtable.waiting.dto.WaitingBatchItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 묶음 접수 작업자는 앱당 2개다. 다른 트랜잭션이 회원 행을 오래 잡아도 작업자를 돌려줘야
// 다른 매장의 묶음 접수가 계속 진행된다.
@SpringBootTest(properties = "waiting.intake.batch.lock-timeout=200ms")
class WaitingBatchLockWaitTest {
    @Autowired WaitingBatchWriter writer;
    @Autowired WaitingIntakeBatcher batcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    private long locked;
    private long free;
    private long store;

    @BeforeEach
    void prepare() {
        locked = member();
        free = member();
        store = jdbc.queryForObject("""
                INSERT INTO store(owner_id,name,category,address,waiting_available,
                    reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
                VALUES (?, 'lock-wait', 'KOREAN','lock-wait',true,90,30,10) RETURNING id
                """, Long.class, free);
        jdbc.update("INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (?,?,10)",
                store, LocalDate.now(clock));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM waiting_receipt_key WHERE member_id IN (?,?)", locked, free);
        jdbc.update("DELETE FROM waiting_receipt WHERE store_id=?", store);
        jdbc.update("DELETE FROM waiting_intake_day WHERE store_id=?", store);
        jdbc.update("DELETE FROM store WHERE id=?", store);
        jdbc.update("DELETE FROM member WHERE id IN (?,?)", locked, free);
    }

    @Test
    void generalPathReleasesTheWriterAsATransientFailure() throws Exception {
        Connection holder = holdMember(locked);
        try {
            // TransientDataAccessException becomes 503 with Retry-After in WaitingController.
            var outcome = CompletableFuture.supplyAsync(() -> batcher.submit(store, item(locked)));
            assertThatFailsWithin(outcome, TransientDataAccessException.class);
        } finally {
            release(holder);
        }
        assertThat(receipts()).isZero();
    }

    @Test
    void singleStatementPathFallsBackAfterTheLockTimeout() throws Exception {
        Connection holder = holdMember(locked);
        try {
            var outcome = CompletableFuture.supplyAsync(() -> writer.regular(store, List.of(item(locked), item(free))));
            assertThat(outcome.get(3, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release(holder);
        }
        assertThat(receipts()).isZero();
    }

    private Connection holdMember(long member) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT id FROM member WHERE id=" + member + " FOR UPDATE");
        }
        return connection;
    }

    private static void release(Connection connection) throws Exception {
        connection.rollback();
        connection.close();
    }

    private static void assertThatFailsWithin(CompletableFuture<?> outcome, Class<? extends Throwable> expected)
            throws Exception {
        try {
            outcome.get(3, TimeUnit.SECONDS);
            throw new AssertionError("expected " + expected.getSimpleName());
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(expected);
        }
    }

    private long receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM waiting_receipt WHERE store_id=?", Long.class, store);
    }

    private long member() {
        return jdbc.queryForObject("""
                INSERT INTO member(name,phone,user_type) VALUES ('lock-wait','00000000000','CUSTOMER') RETURNING id
                """, Long.class);
    }

    private WaitingBatchItem item(long member) {
        return new WaitingBatchItem(new WaitingBatchItem.Key(member, "lock-wait-" + member), 2);
    }
}
