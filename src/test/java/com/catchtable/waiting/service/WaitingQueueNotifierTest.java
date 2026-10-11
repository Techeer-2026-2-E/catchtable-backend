package com.catchtable.waiting.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.service.NotificationService;
import com.catchtable.waiting.dto.WaitingPositionPayload;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.entity.WaitingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행
// @Transactional 없음 → 리스너가 새 트랜잭션(REQUIRES_NEW)에서 커밋된 데이터를 읽으므로 직접 넣고 정리한다
@SpringBootTest
class WaitingQueueNotifierTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private NotificationService notificationService;

    private final List<Long> storeIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private Long storeId;

    @BeforeEach
    void setUp() {
        storeId = insertStore();
    }

    // 웨이팅 → 매장 → 회원 순으로 정리 (FK)
    @AfterEach
    void tearDown() {
        storeIds.forEach(id -> jdbcTemplate.update("DELETE FROM waiting WHERE store_id = ?", id));
        storeIds.forEach(id -> jdbcTemplate.update("DELETE FROM store WHERE id = ?", id));
        memberIds.forEach(id -> jdbcTemplate.update("DELETE FROM member WHERE id = ?", id));
    }

    private Long insertMember(String name, String userType) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES (?, '010-0000-0000', ?)
                RETURNING id""", Long.class, name, userType);
        memberIds.add(id);
        return id;
    }

    private Long insertStore() {
        Long ownerId = insertMember("점주", "OWNER");
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, '대기열 테스트 매장', 'KOREAN', '서울 강남구', TRUE, 120, 30, 10)
                RETURNING id""", Long.class, ownerId);
        storeIds.add(id);
        return id;
    }

    // 웨이팅 한 건을 새 고객으로 넣고 웨이팅 id를 돌려준다
    private Long insertWaiting(Long storeId, LocalDate date, int waitNumber, WaitingStatus status) {
        Long memberId = insertMember("고객" + waitNumber, "CUSTOMER");
        return jdbcTemplate.queryForObject("""
                INSERT INTO waiting (member_id, store_id, waiting_date, party_count, wait_number, status,
                                     created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?, now(), now())
                RETURNING id""", Long.class, memberId, storeId, date, waitNumber, status.name());
    }

    private Long memberOf(Long waitingId) {
        return jdbcTemplate.queryForObject("SELECT member_id FROM waiting WHERE id = ?", Long.class, waitingId);
    }

    private List<CustomerNotificationEvent> sentEvents(int expectedCount) {
        ArgumentCaptor<CustomerNotificationEvent> captor = ArgumentCaptor.forClass(CustomerNotificationEvent.class);
        verify(notificationService, times(expectedCount)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("진행 중 웨이팅에 접수순으로 순번·앞 팀 수를 각 고객에게 보낸다")
    void sendPositionsInOrder() {
        insertWaiting(storeId, TODAY, 1, WaitingStatus.SEATED);     // 종료 → 순번에서 빠짐
        Long second = insertWaiting(storeId, TODAY, 2, WaitingStatus.CALLED);
        Long third = insertWaiting(storeId, TODAY, 3, WaitingStatus.WAITING);
        Long fourth = insertWaiting(storeId, TODAY, 4, WaitingStatus.CONFIRMED);

        publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));

        List<CustomerNotificationEvent> events = sentEvents(3);
        assertThat(events)
                .extracting(CustomerNotificationEvent::memberId, CustomerNotificationEvent::targetId,
                        CustomerNotificationEvent::payload)
                .containsExactly(
                        tuple(memberOf(second), second, new WaitingPositionPayload(second, 1, 0, WaitingStatus.CALLED)),
                        tuple(memberOf(third), third, new WaitingPositionPayload(third, 2, 1, WaitingStatus.WAITING)),
                        tuple(memberOf(fourth), fourth, new WaitingPositionPayload(fourth, 3, 2, WaitingStatus.CONFIRMED)));
        assertThat(events).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(NotificationType.WAITING_POSITION_CHANGED);
            assertThat(event.message()).isNull();   // 화면 숫자 갱신용이라 문구 없음
        });
    }

    @Test
    @DisplayName("종료된 웨이팅(취소·만료·착석)에는 보내지 않는다")
    void skipEndedWaitings() {
        insertWaiting(storeId, TODAY, 1, WaitingStatus.CANCELED);
        insertWaiting(storeId, TODAY, 2, WaitingStatus.EXPIRED);
        insertWaiting(storeId, TODAY, 3, WaitingStatus.SEATED);
        Long active = insertWaiting(storeId, TODAY, 4, WaitingStatus.WAITING);

        publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));

        assertThat(sentEvents(1).get(0).payload())
                .isEqualTo(new WaitingPositionPayload(active, 1, 0, WaitingStatus.WAITING));
    }

    @Test
    @DisplayName("다른 매장·다른 날짜의 대기열에는 보내지 않는다")
    void onlyTargetQueue() {
        Long target = insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING);
        insertWaiting(storeId, TODAY.plusDays(1), 1, WaitingStatus.WAITING);   // 같은 매장, 다른 날
        insertWaiting(insertStore(), TODAY, 1, WaitingStatus.WAITING);         // 다른 매장, 같은 날

        publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));

        assertThat(sentEvents(1)).extracting(CustomerNotificationEvent::targetId).containsExactly(target);
    }

    @Test
    @DisplayName("진행 중 웨이팅이 없으면 아무것도 보내지 않는다")
    void emptyQueue() {
        insertWaiting(storeId, TODAY, 1, WaitingStatus.SEATED);

        publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));

        verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("상태 변경 트랜잭션이 커밋된 뒤 바뀐 상태 기준으로 보낸다")
    void sendAfterCommitWithChangedState() {
        Long first = insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING);
        Long second = insertWaiting(storeId, TODAY, 2, WaitingStatus.WAITING);

        // 다른 기능(예: 착석 처리)이 상태를 바꾸고 같은 트랜잭션에서 발행하는 상황
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE waiting SET status = 'SEATED' WHERE id = ?", first);
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));
            verify(notificationService, never()).send(any());   // 커밋 전에는 보내지 않는다
        });

        assertThat(sentEvents(1).get(0).payload())
                .isEqualTo(new WaitingPositionPayload(second, 1, 0, WaitingStatus.WAITING));
    }

    @Test
    @DisplayName("상태 변경 트랜잭션이 롤백되면 보내지 않는다")
    void noSendOnRollback() {
        insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING);

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));
            status.setRollbackOnly();
        });

        verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("한 고객 전송이 실패해도 나머지 고객에게는 보낸다")
    void continueWhenOneFails() {
        insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING);
        insertWaiting(storeId, TODAY, 2, WaitingStatus.WAITING);
        insertWaiting(storeId, TODAY, 3, WaitingStatus.WAITING);
        doThrow(new IllegalStateException("전송 실패")).doNothing().when(notificationService).send(any());

        publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));

        sentEvents(3);
    }
}
