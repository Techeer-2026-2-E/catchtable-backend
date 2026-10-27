package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 스케줄 비활성화, 실시간 스케줄과 겹치지 않는 미래 고정 시각
@SpringBootTest(properties = "waiting.expiry-interval-ms=3600000")
class WaitingAutoExpireConcurrencyTest {

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2030-01-10T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired WaitingExpiryScheduler scheduler;
    @Autowired OwnerWaitingService ownerWaitingService;
    @Autowired WaitingRepository waitingRepository;
    @Autowired WaitingEndHistoryRepository historyRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    @Timeout(20)
    void autoExpiryAndOwnerEntryApplyOnlyOne() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Long ownerId = insertMember("owner-" + suffix + "@example.com", "OWNER");
        Long customerId = insertMember("customer-" + suffix + "@example.com", "CUSTOMER");
        Long storeId = null;
        try {
            storeId = jdbc.queryForObject("""
                    INSERT INTO store (
                        owner_id, name, category, address, waiting_available,
                        reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                    )
                    VALUES (?, ?, 'KOREAN', 'Seoul', true, 90, 30, 10)
                    RETURNING id
                    """, Long.class, ownerId, "expiry-" + suffix);
            // 공용 DB에 남은 만료 대상 선처리
            scheduler.expireCalledWaitings();
            OffsetDateTime now = OffsetDateTime.now(clock);
            Long waitingId = jdbc.queryForObject("""
                    INSERT INTO waiting (
                        member_id, store_id, waiting_date, party_count, wait_number, status,
                        called_at, call_expires_at, call_count, created_at, updated_at
                    )
                    VALUES (?, ?, ?, 2, 1, 'CALLED', ?, ?, 1, now(), now())
                    RETURNING id
                    """, Long.class, customerId, storeId, now.toLocalDate(),
                    now.minusMinutes(11), now.minusMinutes(1));
            CountDownLatch start = new CountDownLatch(1);
            int expired;
            boolean entered;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var expiry = executor.submit(() -> { start.await(); return scheduler.expireCalledWaitings(); });
                var entry = executor.submit(() -> {
                    start.await();
                    try {
                        ownerWaitingService.enterWaiting(ownerId, waitingId);
                        return true;
                    } catch (BusinessException exception) {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WAITING_NOT_ENTERABLE);
                        return false;
                    }
                });
                start.countDown();
                expired = expiry.get(10, TimeUnit.SECONDS);
                entered = entry.get(10, TimeUnit.SECONDS);
            }

            assertThat(expired == 1).isNotEqualTo(entered);
            Waiting result = waitingRepository.findById(waitingId).orElseThrow();
            var history = historyRepository.findAllByWaitingIdOrderByIdAsc(waitingId);
            if (entered) {
                assertThat(result.getStatus()).isEqualTo(WaitingStatus.SEATED);
                assertThat(result.getEndReason()).isNull();
                assertThat(history).isEmpty();
            } else {
                assertThat(result.getStatus()).isEqualTo(WaitingStatus.NO_SHOW);
                assertThat(result.getEndReason()).isEqualTo(WaitingEndReason.AUTO_EXPIRED);
                assertThat(result.getEnteredAt()).isNull();
                assertThat(history).singleElement()
                        .satisfies(row -> assertThat(row.getActorId()).isNull());
            }
        } finally {
            if (storeId != null) {
                jdbc.update("delete from waiting_end_history where waiting_id in (select id from waiting where store_id = ?)",
                        storeId);
                jdbc.update("delete from waiting where store_id = ?", storeId);
                jdbc.update("delete from store where id = ?", storeId);
            }
            jdbc.update("delete from member where id in (?, ?)", ownerId, customerId);
        }
    }

    private Long insertMember(String email, String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'test', '010-0000-0000', ?)
                RETURNING id
                """, Long.class, email, type);
    }
}
