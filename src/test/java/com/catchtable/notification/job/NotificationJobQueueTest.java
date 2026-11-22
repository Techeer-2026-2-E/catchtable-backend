package com.catchtable.notification.job;

import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.repository.NotificationJobRepository;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.waiting.dto.WaitingPositionPayload;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.entity.WaitingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행
// @Transactional 없음 → 커밋돼야 전송 스레드가 job을 가져가므로 직접 넣고 정리한다
@SpringBootTest
class NotificationJobQueueTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationJobWorker worker;
    @Autowired NotificationJobRepository jobRepository;
    @Autowired SseEmitterRepository emitters;

    private final List<Long> storeIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final Map<Long, SseEmitter> connected = new LinkedHashMap<>();
    private Long storeId;

    @BeforeEach
    void setUp() {
        storeId = insertStore();
    }

    // 연결 → 웨이팅 → 매장(job은 FK CASCADE로 같이 삭제) → 회원 순으로 정리
    @AfterEach
    void tearDown() {
        connected.forEach(emitters::delete);
        storeIds.forEach(id -> jdbc.update("DELETE FROM waiting WHERE store_id = ?", id));
        storeIds.forEach(id -> jdbc.update("DELETE FROM store WHERE id = ?", id));
        memberIds.forEach(id -> jdbc.update("DELETE FROM member WHERE id = ?", id));
    }

    @Test
    @DisplayName("보내는 순간의 대기열로 순번을 계산해 진행 중인 팀에게만 보낸다")
    void positionsUseCurrentQueue() {
        connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.SEATED));
        Long second = insertWaiting(storeId, TODAY, 2, WaitingStatus.CALLED);
        Long third = insertWaiting(storeId, TODAY, 3, WaitingStatus.WAITING);
        Long fourth = insertWaiting(storeId, TODAY, 4, WaitingStatus.CONFIRMED);
        List.of(second, third, fourth).forEach(this::connect);

        publish(new WaitingQueueChangedEvent(storeId, TODAY));

        assertThat(sentPositions()).containsExactly(
                new WaitingPositionPayload(second, 1, 0, WaitingStatus.CALLED),
                new WaitingPositionPayload(third, 2, 1, WaitingStatus.WAITING),
                new WaitingPositionPayload(fourth, 3, 2, WaitingStatus.CONFIRMED));
        assertThat(queueJob())
                .containsEntry("result", "SENT")
                .containsEntry("recipients", 3)
                .containsEntry("sent_count", 3);
    }

    @Test
    @DisplayName("취소·노쇼·착석한 팀은 순번 대상에서 빠진다")
    void endedTeamsAreExcluded() {
        connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.CANCELED));
        connect(insertWaiting(storeId, TODAY, 2, WaitingStatus.NO_SHOW));
        connect(insertWaiting(storeId, TODAY, 3, WaitingStatus.SEATED));
        Long active = insertWaiting(storeId, TODAY, 4, WaitingStatus.WAITING);
        connect(active);

        publish(new WaitingQueueChangedEvent(storeId, TODAY));

        assertThat(sentPositions()).containsExactly(new WaitingPositionPayload(active, 1, 0, WaitingStatus.WAITING));
    }

    @Test
    @DisplayName("요청한 매장·날짜의 대기열에만 보낸다")
    void onlyRequestedStoreAndDate() {
        SseEmitter target = connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING));
        SseEmitter otherDate = connect(insertWaiting(storeId, TODAY.plusDays(1), 1, WaitingStatus.WAITING));
        SseEmitter otherStore = connect(insertWaiting(insertStore(), TODAY, 1, WaitingStatus.WAITING));

        publish(new WaitingQueueChangedEvent(storeId, TODAY));
        drain();

        assertThat(sentMessages(target)).hasSize(1);
        verifyNoInteractions(otherDate, otherStore);
    }

    @Test
    @DisplayName("연결된 손님이 없으면 대상 수만 남기고 NO_CONNECTION으로 끝낸다")
    void noConnectionStillFinishesJob() {
        insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING);

        publish(new WaitingQueueChangedEvent(storeId, TODAY));
        drain();

        assertThat(queueJob())
                .containsEntry("status", "DONE")
                .containsEntry("result", "NO_CONNECTION")
                .containsEntry("recipients", 1)
                .containsEntry("sent_count", 0);
    }

    @Test
    @DisplayName("업무 TX 안에서는 저장만 하고, 커밋된 뒤에 보낸다")
    void savesInBusinessTransactionAndSendsAfterCommit() {
        SseEmitter emitter = connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING));

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));
            assertThat(queueJob()).containsEntry("status", "PENDING");
            verifyNoInteractions(emitter);
        });

        assertThat(sentPositions()).hasSize(1);
    }

    @Test
    @DisplayName("저장한 시점이 아니라 보내는 시점의 대기열을 쓴다")
    void usesQueueAtSendTime() {
        Long first = insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING);
        Long second = insertWaiting(storeId, TODAY, 2, WaitingStatus.WAITING);
        connect(second);

        // recorder를 거치지 않고 job만 저장 → 전송 스레드가 깨어나지 않음
        jobRepository.insertQueueIfAbsent(storeId, TODAY, OffsetDateTime.now());
        jdbc.update("UPDATE waiting SET status = 'SEATED' WHERE id = ?", first);

        assertThat(sentPositions()).containsExactly(new WaitingPositionPayload(second, 1, 0, WaitingStatus.WAITING));
    }

    @Test
    @DisplayName("롤백되면 job이 남지 않고 보내지 않는다")
    void rollbackLeavesNoJob() {
        SseEmitter emitter = connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING));

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));
            status.setRollbackOnly();
        });
        drain();

        assertThat(jobCount()).isZero();
        verifyNoInteractions(emitter);
    }

    @Test
    @DisplayName("대기 중인 QUEUE 작업은 매장·날짜당 1개로 합쳐진다")
    void pendingQueueJobIsCoalesced() {
        connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING));

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId, TODAY));
        });

        assertThat(sentPositions()).hasSize(1);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 손님에게 보내다 실패해도 나머지 손님에게는 보낸다")
    void failureOfOneRecipientDoesNotStopOthers() throws IOException {
        SseEmitter broken = connect(insertWaiting(storeId, TODAY, 1, WaitingStatus.WAITING));
        doThrow(new IllegalStateException("closed")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        connect(insertWaiting(storeId, TODAY, 2, WaitingStatus.WAITING));
        connect(insertWaiting(storeId, TODAY, 3, WaitingStatus.WAITING));

        publish(new WaitingQueueChangedEvent(storeId, TODAY));
        drain();

        assertThat(queueJob()).containsEntry("recipients", 3).containsEntry("sent_count", 2);
    }

    // ---- 도우미 ----

    private void publish(WaitingQueueChangedEvent event) {
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private void drain() {
        NotificationJobTestDrain.forStore(jdbc, worker, storeId).done();
    }

    // 손님 앱이 SSE에 연결된 상태를 만든다
    private SseEmitter connect(Long waitingId) {
        Long memberId = memberOf(waitingId);
        SseEmitter emitter = mock(SseEmitter.class);
        emitters.save(memberId, emitter);
        connected.put(memberId, emitter);
        return emitter;
    }

    // 연결된 손님들이 받은 순번 (순번 순서)
    private List<WaitingPositionPayload> sentPositions() {
        drain();
        return connected.values().stream()
                .flatMap(emitter -> sentMessages(emitter).stream())
                .map(message -> (WaitingPositionPayload) message.payload())
                .sorted(Comparator.comparingInt(WaitingPositionPayload::currentPosition))
                .toList();
    }

    // mock 연결에 실제로 보낸 알림 본문
    private List<NotificationMessage> sentMessages(SseEmitter emitter) {
        return mockingDetails(emitter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .map(invocation -> (Object) invocation.getArgument(0))
                .filter(SseEmitter.SseEventBuilder.class::isInstance)
                .flatMap(builder -> ((SseEmitter.SseEventBuilder) builder).build().stream())
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(NotificationMessage.class::isInstance)
                .map(NotificationMessage.class::cast)
                .toList();
    }

    private Map<String, Object> queueJob() {
        return jdbc.queryForMap("""
                SELECT status, result, recipients, sent_count FROM notification_job
                WHERE kind = 'QUEUE' AND store_id = ?""", storeId);
    }

    private long jobCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_job WHERE store_id = ?", Long.class, storeId);
    }

    private Long insertMember(String name, String userType) {
        Long id = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES (?, '010-0000-0000', ?)
                RETURNING id""", Long.class, name, userType);
        memberIds.add(id);
        return id;
    }

    private Long insertStore() {
        Long ownerId = insertMember("점주", "OWNER");
        Long id = jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, '대기열 테스트 매장', 'KOREAN', '서울 강남구', TRUE, 120, 30, 10)
                RETURNING id""", Long.class, ownerId);
        storeIds.add(id);
        return id;
    }

    private Long insertWaiting(Long storeId, LocalDate date, int waitNumber, WaitingStatus status) {
        Long memberId = insertMember("고객" + waitNumber, "CUSTOMER");
        return jdbc.queryForObject("""
                INSERT INTO waiting (member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                                     created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?, ?, now(), now())
                RETURNING id""", Long.class, memberId, storeId, date, waitNumber, waitNumber, status.name());
    }

    private Long memberOf(Long waitingId) {
        return jdbc.queryForObject("SELECT member_id FROM waiting WHERE id = ?", Long.class, waitingId);
    }
}
