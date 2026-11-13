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

    @Autowired private com.catchtable.notification.durability.WaitingSseWorker worker;
    @Autowired private com.catchtable.notification.repository.SseEmitterRepository emitters;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;

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
                INSERT INTO waiting (member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                                     created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?, ?, now(), now())
                RETURNING id""", Long.class, memberId, storeId, date, waitNumber, waitNumber, status.name());
    }

    private Long memberOf(Long waitingId) {
        return jdbcTemplate.queryForObject("SELECT member_id FROM waiting WHERE id = ?", Long.class, waitingId);
    }

    private void publish(WaitingQueueChangedEvent event) {
        transactionTemplate.executeWithoutResult(s -> publisher.publishEvent(event));
    }
    private void drain() {
        com.catchtable.notification.durability.WaitingSseTestDrain.forStore(jdbcTemplate,worker,storeId).done();
    }
    private List<CustomerNotificationEvent> sentEvents(int expectedCount) {
        drain();
        var events=jdbcTemplate.query("""
                SELECT t.member_id,t.waiting_id,t.message::text FROM waiting_sse_target t
                JOIN waiting_sse_root r ON r.id=t.root_id WHERE r.store_id=?
                ORDER BY (t.message->'payload'->>'currentPosition')::integer
                """,(r,n)->{
            try {
                var json=mapper.readTree(r.getString(3));
                var payload=mapper.treeToValue(json.get("payload"),WaitingPositionPayload.class);
                return new CustomerNotificationEvent(r.getLong(1),NotificationType.WAITING_POSITION_CHANGED,r.getLong(2),null,payload);
            } catch(Exception e) { throw new IllegalStateException(e); }
        },storeId);
        assertThat(events).hasSize(expectedCount);
        return events;
    }
    @Test void positionsUseCurrentQueueAndPreservePayload() {
        insertWaiting(storeId,TODAY,1,WaitingStatus.SEATED);
        Long second=insertWaiting(storeId,TODAY,2,WaitingStatus.CALLED);
        Long third=insertWaiting(storeId,TODAY,3,WaitingStatus.WAITING);
        Long fourth=insertWaiting(storeId,TODAY,4,WaitingStatus.CONFIRMED);
        publish(new WaitingQueueChangedEvent(storeId,TODAY));
        assertThat(sentEvents(3)).extracting(CustomerNotificationEvent::memberId,CustomerNotificationEvent::targetId,CustomerNotificationEvent::payload)
                .containsExactly(tuple(memberOf(second),second,new WaitingPositionPayload(second,1,0,WaitingStatus.CALLED)),
                        tuple(memberOf(third),third,new WaitingPositionPayload(third,2,1,WaitingStatus.WAITING)),
                        tuple(memberOf(fourth),fourth,new WaitingPositionPayload(fourth,3,2,WaitingStatus.CONFIRMED)));
    }
    @Test void endedTeamsAreExcluded() {
        insertWaiting(storeId,TODAY,1,WaitingStatus.CANCELED);insertWaiting(storeId,TODAY,2,WaitingStatus.NO_SHOW);
        insertWaiting(storeId,TODAY,3,WaitingStatus.SEATED);Long active=insertWaiting(storeId,TODAY,4,WaitingStatus.WAITING);
        publish(new WaitingQueueChangedEvent(storeId,TODAY));
        assertThat(sentEvents(1).getFirst().payload()).isEqualTo(new WaitingPositionPayload(active,1,0,WaitingStatus.WAITING));
    }
    @Test void onlyRequestedStoreAndDate() {
        Long target=insertWaiting(storeId,TODAY,1,WaitingStatus.WAITING);
        insertWaiting(storeId,TODAY.plusDays(1),1,WaitingStatus.WAITING);
        insertWaiting(insertStore(),TODAY,1,WaitingStatus.WAITING);
        publish(new WaitingQueueChangedEvent(storeId,TODAY));
        assertThat(sentEvents(1)).extracting(CustomerNotificationEvent::targetId).containsExactly(target);
    }
    @Test void emptyQueueHasExplicitExpansionZero() {
        insertWaiting(storeId,TODAY,1,WaitingStatus.SEATED);publish(new WaitingQueueChangedEvent(storeId,TODAY));sentEvents(0);
        assertThat(jdbcTemplate.queryForObject("SELECT expected_targets FROM waiting_sse_root WHERE store_id=?",Integer.class,storeId)).isZero();
    }
    @Test void evaluatesCommittedProcessingStateAndSendsAfterCommit() {
        Long first=insertWaiting(storeId,TODAY,1,WaitingStatus.WAITING);
        Long second=insertWaiting(storeId,TODAY,2,WaitingStatus.WAITING);
        transactionTemplate.executeWithoutResult(s->{
            publisher.publishEvent(new WaitingQueueChangedEvent(storeId,TODAY));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM waiting_sse_target t JOIN waiting_sse_root r ON r.id=t.root_id WHERE r.store_id=?",Long.class,storeId)).isZero();
        });
        jdbcTemplate.update("UPDATE waiting SET status='SEATED' WHERE id=?",first);
        assertThat(sentEvents(1).getFirst().payload()).isEqualTo(new WaitingPositionPayload(second,1,0,WaitingStatus.WAITING));
    }
    @Test void rollbackHasNoRootOrTargets() {
        insertWaiting(storeId,TODAY,1,WaitingStatus.WAITING);
        transactionTemplate.executeWithoutResult(s->{publisher.publishEvent(new WaitingQueueChangedEvent(storeId,TODAY));s.setRollbackOnly();});
        sentEvents(0);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE store_id=?",Long.class,storeId)).isZero();
    }
    @Test void failureOfOneRecipientDoesNotStopOthers() throws Exception {
        var registered=new java.util.LinkedHashMap<Long,org.springframework.web.servlet.mvc.method.annotation.SseEmitter>();
        for(int i=1;i<=3;i++) {
            Long waiting=insertWaiting(storeId,TODAY,i,WaitingStatus.WAITING);Long member=memberOf(waiting);
            var emitter=org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
            if(i==1) org.mockito.Mockito.doThrow(new IllegalStateException("closed")).when(emitter).send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
            registered.put(member,emitter);emitters.save(member,emitter);
        }
        try {
            publish(new WaitingQueueChangedEvent(storeId,TODAY));sentEvents(3);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM waiting_sse_target t JOIN waiting_sse_root r ON r.id=t.root_id WHERE r.store_id=? AND t.status='SENT'",Long.class,storeId)).isEqualTo(2);
        } finally {registered.forEach(emitters::delete);}
    }
}
