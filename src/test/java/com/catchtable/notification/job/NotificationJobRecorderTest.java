package com.catchtable.notification.job;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 PostgreSQL로 트랜잭션을 열어 저장·전송 시점을 확인한다
@SpringBootTest(properties = "notification.job.write-timeout=6s")
class NotificationJobRecorderTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired SseEmitterRepository repository;
    @Autowired NotificationJobWorker worker;
    @Autowired JdbcTemplate jdbc;

    private Long memberId;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        memberId = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('고객', '010-0000-0000', 'CUSTOMER')
                RETURNING id""", Long.class);
        emitter = mock(SseEmitter.class);
        repository.save(memberId, emitter);
    }

    // 알림함·알림 작업은 회원 FK CASCADE로 같이 삭제
    @AfterEach
    void tearDown() {
        repository.delete(memberId, emitter);
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
    }

    @Test
    @DisplayName("트랜잭션이 커밋되면 알림을 보낸다")
    void sendAfterCommit() throws IOException {
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jobResults()).containsExactly("SENT"));

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(jobResults()).containsExactly("SENT");
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 작업도 남지 않고 보내지 않는다")
    void noSendOnRollback() throws IOException {
        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(event());
            status.setRollbackOnly();
        });
        drain();

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(jobResults()).isEmpty();
    }

    @Test
    @DisplayName("커밋 전에는 저장만 하고 보내지 않는다")
    void notBeforeCommit() throws IOException {
        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(event());
            assertThat(jdbc.queryForList("SELECT status FROM notification_job WHERE member_id = ?",
                    String.class, memberId)).containsExactly("PENDING");
            try {
                verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        drain();

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("트랜잭션 밖에서 발행하면 거부한다 (업무 변경과 같이 저장해야 하므로)")
    void rejectsWithoutTransaction() throws IOException {
        assertThatThrownBy(() -> publisher.publishEvent(event()))
                .isInstanceOf(IllegalTransactionStateException.class);

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(jobResults()).isEmpty();
    }

    private CustomerNotificationEvent event() {
        return new CustomerNotificationEvent(memberId, NotificationType.RESERVATION_CANCELED, 10L, "예약이 취소되었습니다");
    }

    private void drain() {
        NotificationJobTestDrain.forMember(jdbc, worker, memberId).done();
    }

    private List<String> jobResults() {
        return jdbc.queryForList("SELECT result FROM notification_job WHERE member_id = ? ORDER BY id",
                String.class, memberId);
    }

    @Test
    void anotherMemberReceivesWhileTheFirstWriteIsPending() throws Exception {
        Long other = jdbc.queryForObject("""
                INSERT INTO member(name,phone,user_type) VALUES ('테스트', '010-0000-0000', 'CUSTOMER') RETURNING id
                """, Long.class);
        var healthy = mock(SseEmitter.class);
        repository.save(other, healthy);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("첫 쓰기가 제시간에 종료되지 않음");
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try {
            transactionTemplate.executeWithoutResult(tx -> publisher.publishEvent(event()));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            transactionTemplate.executeWithoutResult(tx -> {
                publisher.publishEvent(event());
                publisher.publishEvent(new CustomerNotificationEvent(other, NotificationType.RESERVATION_CANCELED, 20L, "취소"));
            });
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                verify(healthy).send(any(SseEmitter.SseEventBuilder.class));
                assertThat(jdbc.queryForObject("SELECT result FROM notification_job WHERE member_id = ?", String.class, other))
                        .isEqualTo("SENT");
            });
            assertThat(jdbc.queryForList("SELECT status FROM notification_job WHERE member_id = ? ORDER BY id",
                    String.class, memberId)).containsExactly("PROCESSING", "PENDING");
        } finally {
            release.countDown();
            try {
                drain();
                NotificationJobTestDrain.forMember(jdbc, worker, other).done();
            } finally {
                repository.delete(other, healthy);
                jdbc.update("DELETE FROM member WHERE id = ?", other);
            }
        }
        assertThat(jobResults()).containsExactly("SENT", "SENT");
    }
}
