package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJobKind;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트 전용: 범위 안의 알림 작업이 모두 끝날 때까지 전송 스레드를 깨우며 기다린다
public final class NotificationJobTestDrain {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final JdbcTemplate jdbc;
    private final NotificationJobWorker worker;
    private final String scope;
    private final Object[] args;

    private NotificationJobTestDrain(JdbcTemplate jdbc, NotificationJobWorker worker, String scope, Object... args) {
        this.jdbc = jdbc;
        this.worker = worker;
        this.scope = scope;
        this.args = args;
    }

    // 매장의 REFRESH·QUEUE + 그 매장 웨이팅의 손님 알림
    public static NotificationJobTestDrain forStore(JdbcTemplate jdbc, NotificationJobWorker worker, Long storeId) {
        return new NotificationJobTestDrain(jdbc, worker, """
                (store_id = ?
                 OR (kind = 'NOTIFY' AND event_type LIKE 'WAITING_%'
                     AND target_id IN (SELECT id FROM waiting WHERE store_id = ?)))
                """, storeId, storeId);
    }

    // 회원 한 명의 손님 알림 (예약 알림 등)
    public static NotificationJobTestDrain forMember(JdbcTemplate jdbc, NotificationJobWorker worker, Long memberId) {
        return new NotificationJobTestDrain(jdbc, worker, "member_id = ?", memberId);
    }

    public void done() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("업무 TX가 커밋된 뒤에 기다려야 한다").isFalse();
        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            worker.wake(NotificationJobKind.NOTIFY);
            worker.wake(NotificationJobKind.QUEUE); // REFRESH·QUEUE 스레드
            assertThat(unfinished()).as("5초 안에 끝나지 않은 알림 작업").isZero();
        });
    }

    private long unfinished() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_job WHERE status IN ('PENDING', 'PROCESSING') AND " + scope,
                Long.class, args);
    }
}
