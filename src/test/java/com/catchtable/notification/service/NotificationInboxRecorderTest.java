package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.waiting.dto.WaitingEventReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 커밋·롤백을 실제로 보려고 테스트 트랜잭션 없이 실행하고, 끝나면 회원을 지운다 (알림은 CASCADE)
@SpringBootTest
class NotificationInboxRecorderTest {

    @Autowired NotificationInboxRecorder recorder;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private Long memberId;

    @BeforeEach
    void setUp() {
        memberId = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('알림함', '010-0000-0000', 'CUSTOMER')
                RETURNING id""", Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
    }

    private CustomerNotificationEvent reservationCanceled() {
        return new CustomerNotificationEvent(memberId, NotificationType.RESERVATION_CANCELED, 10L, "예약이 취소되었습니다");
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE member_id = ?", Integer.class, memberId);
    }

    @Test
    @DisplayName("업무 트랜잭션에서 이벤트를 발행하면 커밋 후 알림함에 남는다")
    void savedOnCommit() {
        CustomerNotificationEvent event = reservationCanceled();
        tx.executeWithoutResult(s -> publisher.publishEvent(event));

        var row = jdbc.queryForMap("""
                SELECT type, target_id, message, event_key, read_at, deleted_at
                FROM notification WHERE member_id = ?""", memberId);
        assertThat(row.get("type")).isEqualTo("RESERVATION_CANCELED");
        assertThat(row.get("target_id")).isEqualTo(10L);
        assertThat(row.get("message")).isEqualTo("예약이 취소되었습니다");
        assertThat(row.get("event_key")).isEqualTo(event.deliveryKey() + ":RESERVATION_CANCELED");
        assertThat(row.get("read_at")).isNull();
        assertThat(row.get("deleted_at")).isNull();
    }

    @Test
    @DisplayName("업무 트랜잭션이 롤백되면 알림함에도 남지 않는다")
    void notSavedOnRollback() {
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(reservationCanceled());
            s.setRollbackOnly();
        });

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 받아도 예외 없이 따로 저장한다")
    void savedWithoutTransaction() {
        recorder.record(reservationCanceled());

        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 한 건만 저장하고, 업무 트랜잭션은 커밋된다")
    void sameEventOnce() {
        CustomerNotificationEvent event = reservationCanceled();
        tx.executeWithoutResult(s -> {
            recorder.record(event);
            recorder.record(event);
        });

        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("지운 알림과 같은 이벤트가 다시 와도 되살리지 않는다")
    void deletedStaysDeleted() {
        CustomerNotificationEvent event = reservationCanceled();
        recorder.record(event);
        jdbc.update("UPDATE notification SET deleted_at = now() WHERE member_id = ?", memberId);

        recorder.record(event);

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM notification WHERE member_id = ?",
                Boolean.class, memberId)).isTrue();
    }

    @Test
    @DisplayName("웨이팅 알림은 업무 이벤트 키로 저장해 다시 만든 이벤트도 한 건이다")
    void waitingUsesBusinessKey() {
        var ref = new WaitingEventReference(1L, 1, 0, "waiting:1:call:1");
        // 같은 업무 이벤트를 발행 키만 다르게 두 번 만든다
        var first = new CustomerNotificationEvent(memberId, NotificationType.WAITING_CALLED, 1L, "호출").withWaitingEvent(ref);
        var second = new CustomerNotificationEvent(memberId, NotificationType.WAITING_CALLED, 1L, "호출").withWaitingEvent(ref);

        tx.executeWithoutResult(s -> {
            recorder.record(first);
            recorder.record(second);
        });

        assertThat(jdbc.queryForList("SELECT event_key FROM notification WHERE member_id = ?", String.class, memberId))
                .containsExactly("waiting:1:call:1:WAITING_CALLED");
    }

    @Test
    @DisplayName("점주 화면 갱신·순번 변경은 저장하지 않는다")
    void skipsNonInboxTypes() {
        tx.executeWithoutResult(s -> {
            recorder.record(new CustomerNotificationEvent(memberId, NotificationType.WAITING_CHANGED, 1L, "목록 변경"));
            recorder.record(new CustomerNotificationEvent(memberId, NotificationType.WAITING_POSITION_CHANGED, 1L, "순번 변경"));
        });

        assertThat(count()).isZero();
    }
}
