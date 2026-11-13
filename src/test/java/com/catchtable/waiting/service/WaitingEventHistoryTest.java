package com.catchtable.waiting.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.client.AlimtalkGateway;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.repository.AlimtalkOutboxRepository;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.notification.service.AlimtalkDeliveryService;
import com.catchtable.notification.service.NotificationService;
import com.catchtable.notification.service.WaitingSseObservationRecorder;
import com.catchtable.payment.ReconcileTestClock;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.catchtable.waiting.dto.WaitingCallNotification;
import com.catchtable.waiting.dto.WaitingEventReference;
import com.catchtable.waiting.dto.WaitingEventSnapshot;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.repository.WaitingEventRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.catchtable.notification.client.AlimtalkGateway.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"alimtalk.enabled=true", "alimtalk.mode=simulation",
        "alimtalk.initial-delay-ms=3600000", "waiting.expiry-interval-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=6"})
@Import({ReconcileTestClock.Config.class, WaitingSseObservationRecorder.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WaitingEventHistoryTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    @Autowired OwnerWaitingService ownerService;
    @Autowired WaitingService service;
    @Autowired WaitingExpiryScheduler scheduler;
    @Autowired WaitingEventRecorder recorder;
    @Autowired WaitingEventRepository eventRepository;
    @Autowired WaitingSseObservationRecorder sseRecorder;
    @Autowired com.catchtable.notification.durability.WaitingSseWorker sseWorker;
    private final Map<UUID,String> expectedResidualRoots = new java.util.LinkedHashMap<>();
    private void flushSse() {
        com.catchtable.notification.durability.WaitingSseTestDrain.forStore(jdbc,sseWorker,store.getId()).expecting(expectedResidualRoots);
    }
    @Autowired NotificationService notifications;
    @Autowired SseEmitterRepository emitters;
    @Autowired AlimtalkOutboxRepository outbox;
    @Autowired AlimtalkDeliveryService delivery;
    @Autowired WaitingRepository waitings;
    @Autowired MemberRepository members;
    @Autowired StoreRepository stores;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ReconcileTestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean AlimtalkGateway gateway;
    Member owner;
    Member customer;
    Store store;
    Long waitingId;
    final List<Member> additionalMembers = new ArrayList<>();

    @BeforeEach
    void prepare() {
        clock.set(START);
        owner = member(UserType.OWNER);
        customer = member(UserType.CUSTOMER);
        store = stores.save(Store.builder().owner(owner).name("event-test").address("test")
                .category(StoreCategory.KOREAN).reservationDurationMinutes(90)
                .reservationSlotMinutes(30).arrivalGraceMinutes(10).build());
        ownerService.openWaiting(owner.getId(), store.getId());
        WaitingBusinessHourFixture.openNow(jdbc, clock, store.getId());
        waitingId = service.createWaiting(customer.getId(), store.getId(), 2).response().waitingId();
    }

    @AfterEach
    void clean() throws Exception {
        drainObservations();
        jdbc.execute("ALTER TABLE waiting_sse_observation DROP CONSTRAINT IF EXISTS test_reject_sse_result");
        jdbc.execute("ALTER TABLE waiting_event DROP CONSTRAINT IF EXISTS test_reject_event");
        List.copyOf(emitters.findAllByMemberId(customer.getId())).forEach(e -> emitters.delete(customer.getId(), e));
        jdbc.update("delete from waiting_defer_history where waiting_id in (select id from waiting where store_id = ?)", store.getId());
        jdbc.update("delete from waiting_end_history where waiting_id in (select id from waiting where store_id = ?)", store.getId());
        jdbc.update("delete from waiting where store_id = ?", store.getId());
        jdbc.update("delete from store_table where store_id = ?", store.getId());
        WaitingBusinessHourFixture.clear(jdbc, store.getId());
        stores.delete(store);
        members.deleteAll(List.of(owner, customer));
        members.deleteAll(additionalMembers);
    }

    @Test
    void acceptedCallsKeepFirstCallResponsesAndLifetimeResendCounters() throws Exception {
        assertThat(service.createWaiting(customer.getId(), store.getId(), 2).created()).isFalse();
        ownerService.callWaiting(owner.getId(), waitingId);
        clock.set(START.plusSeconds(1));
        service.respondToCall(customer.getId(), waitingId);
        service.respondToCall(customer.getId(), waitingId);
        clock.set(START.plusSeconds(2));
        ownerService.recallWaiting(owner.getId(), waitingId);
        clock.set(START.plusSeconds(3));
        ownerService.callWaiting(owner.getId(), waitingId);
        service.respondToCall(customer.getId(), waitingId);
        ownerService.recallWaiting(owner.getId(), waitingId);
        assertThat(types()).containsExactly("REGISTERED", "CALLED", "RESPONDED", "CALL_RESENT",
                "CALLED_AGAIN", "RESPONDED", "CALL_RESENT");
        assertThat(OffsetDateTime.parse(data("CALLED_AGAIN").path("before").path("respondedAt").asText()))
                .isEqualTo(START.plusSeconds(1));
        assertThat(data("CALLED_AGAIN").path("after").path("respondedAt").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(data("CALLED").path("after").path("calledAt").asText())).isEqualTo(START);
        assertThat(OffsetDateTime.parse(data("CALL_RESENT").path("after").path("callExpiresAt").asText()))
                .isEqualTo(START.plusMinutes(10));
        assertThat(jdbc.queryForList("select call_count || ':' || recall_count from waiting_event "
                + "where waiting_id=? and event_type='CALL_RESENT' order by id", String.class, waitingId))
                .containsExactly("1:1", "2:2");
    }

    @Test
    void sameClockAndSeparateAcceptedCommandsAreNeverCollapsed() {
        transactions.executeWithoutResult(tx -> {
            ownerService.callWaiting(owner.getId(), waitingId);
            ownerService.callWaiting(owner.getId(), waitingId);
            ownerService.recallWaiting(owner.getId(), waitingId);
            ownerService.recallWaiting(owner.getId(), waitingId);
        });
        assertThat(types()).containsExactly("REGISTERED", "CALLED", "CALLED_AGAIN", "CALL_RESENT", "CALL_RESENT");
        assertThat(jdbc.queryForObject("select count(*) from alimtalk_outbox where waiting_id=?", Integer.class, waitingId)).isEqualTo(4);
    }

    @Test
    void rollbackRemovesStateEventAndOutboxAndDoesNotEmit() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        emitters.save(customer.getId(), emitter);
        transactions.executeWithoutResult(tx -> {
            ownerService.callWaiting(owner.getId(), waitingId);
            assertThat(types()).contains("CALLED");
            tx.setRollbackOnly();
        });
        assertThat(types()).containsExactly("REGISTERED");
        assertThat(waitings.findById(waitingId).orElseThrow().getCallCount()).isZero();
        assertThat(outboxIds()).isEmpty();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void eventInsertFailureRollsBackBusinessStateAndExistingHistories() {
        reject("CALLED");
        assertThatThrownBy(() -> ownerService.callWaiting(owner.getId(), waitingId)).isInstanceOf(RuntimeException.class);
        assertThat(waitings.findById(waitingId).orElseThrow().getCallCount()).isZero();
        assertThat(outboxIds()).isEmpty();
        reject("OWNER_CANCELED");
        assertThatThrownBy(() -> ownerService.cancelWaiting(owner.getId(), waitingId, "test"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from waiting_end_history where waiting_id=?", Integer.class, waitingId)).isZero();
        assertThat(types()).containsExactly("REGISTERED");
        assertThat(waitings.findById(waitingId).orElseThrow().getStatus().name()).isEqualTo("WAITING");
    }

    @Test
    void outboxObservationFailureRollsBackCallAndBothNewRecords() {
        reject("ALIMTALK_PENDING");
        assertThatThrownBy(() -> ownerService.callWaiting(owner.getId(), waitingId)).isInstanceOf(RuntimeException.class);
        assertThat(waitings.findById(waitingId).orElseThrow().getCallCount()).isZero();
        assertThat(types()).containsExactly("REGISTERED");
        assertThat(outboxIds()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void duplicateRecordAndListenerDoNotPoisonTheTransaction() {
        transactions.executeWithoutResult(tx -> {
            var waiting = waitings.findByIdForUpdate(waitingId).orElseThrow();
            var before = WaitingEventSnapshot.of(waiting);
            ownerService.callWaiting(owner.getId(), waitingId);
            recorder.record(waiting, "CALLED", "call:1", START, owner.getId(), before);
            publisher.publishEvent(callEvent());
            publisher.publishEvent(callEvent());
            assertThat(tx.isRollbackOnly()).isFalse();
        });
        assertThat(types()).containsExactly("REGISTERED", "CALLED");
        assertThat(outboxIds()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where waiting_id=? and event_type='ALIMTALK_PENDING'",
                Integer.class, waitingId)).isEqualTo(1);
        assertThat(waitings.findById(waitingId).orElseThrow().getCallCount()).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void concurrentRecordingUsesAtomicUniqueKeyWithoutRollbackOnly() throws Exception {
        var ref = new WaitingEventReference(waitingId, 0, 0, "test:" + UUID.randomUUID());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); append(ref); return true; });
            var b = pool.submit(() -> { start.await(); append(ref); return true; });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where event_key=?", Integer.class, ref.eventKey())).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void concurrentCallsRemainSeparateAndConcurrentResponsesRecordOnce() throws Exception {
        race(() -> ownerService.callWaiting(owner.getId(), waitingId));
        race(() -> service.respondToCall(customer.getId(), waitingId));
        assertThat(types()).containsExactly("REGISTERED", "CALLED", "CALLED_AGAIN", "RESPONDED");
    }

    @Test
    void deferSwapAndSeatingPreserveOriginalHistoriesAndIdempotentSeating() {
        var other = member(UserType.CUSTOMER);
        try {
            Long nextId = service.createWaiting(other.getId(), store.getId(), 2).response().waitingId();
            reject("DEFERRED");
            assertThatThrownBy(() -> ownerService.deferWaiting(owner.getId(), waitingId)).isInstanceOf(RuntimeException.class);
            assertThat(waitings.findById(waitingId).orElseThrow().getQueueOrder()).isEqualTo(1);
            assertThat(waitings.findById(nextId).orElseThrow().getQueueOrder()).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from waiting_defer_history where waiting_id=?", Integer.class, waitingId)).isZero();
            jdbc.execute("ALTER TABLE waiting_event DROP CONSTRAINT test_reject_event");
            ownerService.deferWaiting(owner.getId(), waitingId);
            assertThat(types()).containsExactly("REGISTERED", "DEFERRED");
            assertThat(jdbc.queryForObject("select count(*) from waiting_event where waiting_id=? and event_type='QUEUE_MOVED'", Integer.class, nextId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from waiting_defer_history where waiting_id=?", Integer.class, waitingId)).isEqualTo(1);
            ownerService.callWaiting(owner.getId(), waitingId);
            Long tableId = jdbc.queryForObject("insert into store_table(store_id, table_number, capacity) values (?,1,4) returning id", Long.class, store.getId());
            ownerService.enterWaiting(owner.getId(), waitingId, tableId);
            ownerService.enterWaiting(owner.getId(), waitingId, tableId);
            assertThat(types()).containsExactly("REGISTERED", "DEFERRED", "CALLED", "SEATED");
            // Cleanup waits for the enclosing fixture's waiting FK deletion.
            jdbc.update("delete from waiting where id=?", nextId);
        } finally { members.delete(other); }
    }

    @Test
    void customerCancellationAndNoShowCorrectionRemainDistinct() {
        service.cancelWaiting(customer.getId(), waitingId);
        service.cancelWaiting(customer.getId(), waitingId);
        assertThat(types()).containsExactly("REGISTERED", "CUSTOMER_CANCELED");
        waitingId = service.createWaiting(customer.getId(), store.getId(), 2).response().waitingId();
        ownerService.callWaiting(owner.getId(), waitingId);
        ownerService.markNoShow(owner.getId(), waitingId, WaitingEndReason.NO_RESPONSE, "test");
        ownerService.markNoShow(owner.getId(), waitingId, WaitingEndReason.NO_RESPONSE, "test");
        ownerService.markNoShow(owner.getId(), waitingId, WaitingEndReason.NO_SHOW, "corrected");
        assertThat(types()).containsExactly("REGISTERED", "CALLED", "NO_RESPONSE", "END_REASON_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from waiting_end_history where waiting_id=?", Integer.class, waitingId)).isEqualTo(2);
    }

    @Test
    void expiryFailureRollsBackAndLaterRunRecordsOnce() {
        ownerService.callWaiting(owner.getId(), waitingId);
        clock.set(START.plusMinutes(10));
        assertThat(scheduler.expireCalledWaitings()).isZero();
        clock.set(START.plusMinutes(10).plusNanos(1_000));
        reject("AUTO_EXPIRED");
        assertThat(scheduler.expireCalledWaitings()).isZero();
        assertThat(waitings.findById(waitingId).orElseThrow().getStatus().name()).isEqualTo("CALLED");
        jdbc.execute("ALTER TABLE waiting_event DROP CONSTRAINT test_reject_event");
        assertThat(scheduler.expireCalledWaitings()).isEqualTo(1);
        assertThat(scheduler.expireCalledWaitings()).isZero();
        assertThat(types()).containsExactly("REGISTERED", "CALLED", "AUTO_EXPIRED");
    }

    @Test
    void sseRecordsMultipleConnectionsBrokenConnectionAndNoConnectionsWithoutReceiptClaim() throws Exception {
        SseEmitter alive = mock(SseEmitter.class);
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        emitters.save(customer.getId(), alive);
        emitters.save(customer.getId(), broken);
        ownerService.callWaiting(owner.getId(), waitingId);
        JsonNode observed = lastCallObservation();
        assertThat(observed.path("connections").asInt()).isEqualTo(2);
        assertThat(observed.path("attempted").asInt()).isEqualTo(2);
        assertThat(observed.path("serverSendSucceeded").asInt()).isEqualTo(1);
        assertThat(observed.path("serverSendFailed").asInt()).isEqualTo(1);
        assertThat(observed.path("customerReceipt").asText()).isEqualTo("UNKNOWN");
        assertThat(observed.path("businessEventKey").asText()).isEqualTo("waiting:" + waitingId + ":call:1");
        emitters.delete(customer.getId(), alive);
        ownerService.recallWaiting(owner.getId(), waitingId);
        assertThat(lastCallObservation().path("connections").asInt()).isZero();
        assertThat(lastCallObservation().path("attempted").asInt()).isZero();
    }

    @Test
    void resultWriteFailureKeepsCommittedBusinessAndDurableAttempt() throws Exception {
        SseEmitter alive = mock(SseEmitter.class);
        emitters.save(customer.getId(), alive);
        jdbc.execute("ALTER TABLE waiting_sse_observation ADD CONSTRAINT test_reject_sse_result CHECK(phase <> 'SEND_RETURNED') NOT VALID");
        ownerService.callWaiting(owner.getId(), waitingId);
        // Only these two roots intentionally retain MARKED attempts after the injected result-write failure.
        jdbc.queryForList("SELECT id FROM waiting_sse_root WHERE store_id=? AND (event_type='WAITING_CALLED' OR kind='QUEUE')",UUID.class,store.getId())
                .forEach(id -> expectedResidualRoots.put(id,"CLAIMED"));
        assertThat(expectedResidualRoots).hasSize(2);
        drainObservations();
        flushSse();
        verify(alive, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        jdbc.execute("ALTER TABLE waiting_sse_observation DROP CONSTRAINT test_reject_sse_result");
        assertThat(types()).contains("CALLED");
        assertThat(waitings.findById(waitingId).orElseThrow().getCallCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where waiting_id=? and event_type='SSE_EMIT_FINISHED' and details->>'notificationType'='WAITING_CALLED'", Integer.class, waitingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_attempt a JOIN waiting_sse_target t ON t.id=a.target_id WHERE t.waiting_id=? AND a.status='MARKED'", Integer.class,waitingId)).isEqualTo(2);
    }

    @Test
    void retriedSseObservationIsOneRecordWhileNewEmissionHasItsOwnAttempt() throws Exception {
        ownerService.callWaiting(owner.getId(), waitingId);
        String id = UUID.randomUUID().toString();
        sseRecorder.record(callEvent(), id, "EMIT_FINISHED", 0, 0, 0, 0).join();
        sseRecorder.record(callEvent(), id, "EMIT_FINISHED", 0, 0, 0, 0).join();
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where source_type='SSE' and source_id=?", Integer.class, id)).isEqualTo(1);
        transactions.executeWithoutResult(tx -> notifications.send(callEvent()));
        flushSse();
        drainObservations();
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where source_type='SSE' and source_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from waiting_sse_root where waiting_id=? and event_type='WAITING_CALLED'",Integer.class,waitingId)).isEqualTo(2);
    }

    @Test
    void normalTenWaitingFanoutKeepsEightRootsAnd26TargetsForCallAcceptRecallCall() throws Exception {
        for (int i = 0; i < 9; i++) {
            var extra = member(UserType.CUSTOMER);
            additionalMembers.add(extra);
            service.createWaiting(extra.getId(), store.getId(), 2);
        }
        drainObservations();
        Long previousId = jdbc.queryForObject("select coalesce(max(id),0) from waiting_event", Long.class);
        ownerService.callWaiting(owner.getId(), waitingId);
        service.respondToCall(customer.getId(), waitingId);
        ownerService.recallWaiting(owner.getId(), waitingId);
        ownerService.callWaiting(owner.getId(), waitingId);
        drainObservations();
        flushSse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE waiting_id=? AND event_key NOT LIKE '%registered%'",Integer.class,waitingId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_target t JOIN waiting_sse_root r ON r.id=t.root_id WHERE r.waiting_id=? AND r.event_key NOT LIKE '%registered%'",Integer.class,waitingId)).isEqualTo(26);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_root WHERE waiting_id=? AND status<>'DONE'",Integer.class,waitingId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where id>? and source_type='WAITING'", Integer.class, previousId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where id>? and source_type='ALIMTALK_OUTBOX'", Integer.class, previousId)).isEqualTo(3);
    }

    @Test
    void oneInvalidBatchRowDoesNotLoseHealthyNeighborsOrDuplicateTheirKeys() throws Exception {
        ownerService.callWaiting(owner.getId(), waitingId);
        drainObservations();
        reject("SSE_REJECTED");
        var writer = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(sseRecorder, "writer");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        writer.execute(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        String prefix = "batch-" + UUID.randomUUID();
        var event = callEvent();
        java.util.concurrent.CompletableFuture<Void> first, duplicate, bad, last;
        try {
            first = sseRecorder.record(event, prefix + "-first", "EMIT_STARTED", 0, 0, 0, 0);
            duplicate = sseRecorder.record(event, prefix + "-first", "EMIT_STARTED", 0, 0, 0, 0);
            bad = sseRecorder.record(event, prefix + "-bad", "REJECTED", 0, 0, 0, 0);
            last = sseRecorder.record(event, prefix + "-last", "EMIT_FINISHED", 0, 0, 0, 0);
        } finally { release.countDown(); }
        first.get(5, TimeUnit.SECONDS);
        duplicate.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> bad.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        last.get(5, TimeUnit.SECONDS);
        drainObservations();
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where source_id like ?", Integer.class, prefix + "%")).isEqualTo(2);
        assertThat(jdbc.queryForList("select source_id from waiting_event where source_id like ? order by id", String.class, prefix + "%"))
                .containsExactly(prefix + "-first", prefix + "-last");
        assertThat(waitings.findById(waitingId).orElseThrow().getCallCount()).isEqualTo(1);
    }

    @Test
    void alimtalkAcceptanceFinalReportAndUnknownRemainSeparateAppendOnlyObservations() throws Exception {
        ownerService.callWaiting(owner.getId(), waitingId);
        UUID id = outboxIds().getFirst();
        outbox.claimSend(id, START).orElseThrow();
        outbox.recordResult(id, 1, new AlimtalkGateway.Result(ACCEPTED, "test-message", null), START.plusSeconds(1));
        outbox.recordResult(id, 1, new AlimtalkGateway.Result(ACCEPTED, "test-message", null), START.plusSeconds(2));
        outbox.recordResult(id, 1, AlimtalkGateway.Result.unknown("test-message"), START.plusSeconds(3));
        outbox.recordResult(id, 1, new AlimtalkGateway.Result(DELIVERED, "test-message", null), START.plusSeconds(4));
        outbox.recordResult(id, 1, new AlimtalkGateway.Result(PERMANENT_FAILURE, "test-message", "STALE"), START.plusSeconds(5));
        assertThat(alimtalkTypes()).containsExactly("ALIMTALK_PENDING", "ALIMTALK_PROCESSING", "ALIMTALK_ACCEPTED", "ALIMTALK_UNKNOWN", "ALIMTALK_SENT");
        assertThat(data("ALIMTALK_SENT").path("customerReceipt").asText()).isEqualTo("UNKNOWN");
        assertThat(data("ALIMTALK_SENT").path("transportMode").asText()).isEqualTo("simulation");
        assertThat(outbox.find(id).orElseThrow().status()).isEqualTo("SENT");
    }

    @Test
    void deliveryObservationFailureRecoversUnknownAndNeverResendsAmbiguousRequest() {
        ownerService.callWaiting(owner.getId(), waitingId);
        when(gateway.send(any())).thenReturn(new AlimtalkGateway.Result(ACCEPTED, "test-message", null));
        reject("ALIMTALK_ACCEPTED");
        assertThatThrownBy(delivery::poll).isInstanceOf(RuntimeException.class);
        assertThat(outbox.find(outboxIds().getFirst()).orElseThrow().status()).isEqualTo("PROCESSING");
        clock.set(START.plusSeconds(61));
        delivery.poll();
        assertThat(outbox.find(outboxIds().getFirst()).orElseThrow().status()).isEqualTo("UNKNOWN");
        verify(gateway, times(1)).send(any());
        assertThat(alimtalkTypes()).containsExactly("ALIMTALK_PENDING", "ALIMTALK_PROCESSING", "ALIMTALK_UNKNOWN");
    }

    @Test
    void saturatedObservationQueueNeverBlocksSseAndCannotClaimReceipt() throws Exception {
        ownerService.callWaiting(owner.getId(), waitingId);
        drainObservations();
        var writer = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(sseRecorder, "writer");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        writer.execute(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            for (int i = 0; i < 512; i++) writer.execute(() -> {});
            var future = sseRecorder.record(callEvent(), "queue-full", "EMIT_FINISHED", 0, 0, 0, 0);
            assertThat(future.isCompletedExceptionally()).isTrue();
            SseEmitter alive = mock(SseEmitter.class);
            emitters.save(customer.getId(), alive);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> transactions.executeWithoutResult(tx -> notifications.send(callEvent())));
            flushSse();
            verify(alive).send(any(SseEmitter.SseEventBuilder.class));
        } finally { release.countDown(); }
        drainObservations();
        assertThat(jdbc.queryForObject("select count(*) from waiting_event where source_id='queue-full'", Integer.class)).isZero();
    }

    @Test
    void timelineUsesBothIdsCapsRowsAndDoesNotDuplicateLinkedEndHistory() throws Exception {
        ownerService.callWaiting(owner.getId(), waitingId);
        ownerService.cancelWaiting(owner.getId(), waitingId, "private reason");
        drainObservations();
        String query = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/waiting-event-history.sql"))
                .replace("$1", "?").replace("$2", "?").replace("$3", "?");
        var rows = jdbc.queryForList(query, waitingId, store.getId(), 500);
        assertThat(rows).filteredOn(r -> r.get("source_type").equals("WAITING_END_HISTORY"))
                .singleElement().satisfies(r -> assertThat(r.get("record_origin")).isEqualTo("EVENT_V36"));
        assertThat(rows.toString()).doesNotContain("private reason", "01000000000");
        assertThat(jdbc.queryForList(query, waitingId, -1L, 500)).isEmpty();
        assertThat(jdbc.queryForList(query, -1L, store.getId(), 500)).isEmpty();
        assertThat(jdbc.queryForList(query, waitingId, store.getId(), 1)).hasSize(1);
        jdbc.update("""
                insert into waiting_event(event_key,waiting_id,event_type,occurred_at,source_type,source_id,details)
                select 'cap:' || ? || ':' || i, ?, 'TEST', ?, 'TEST', i::text, '{}'::jsonb from generate_series(1,501) i
                """, waitingId, waitingId, START.plusDays(1));
        var capped = jdbc.queryForList(query, waitingId, store.getId(), 9999);
        assertThat(capped).hasSize(500);
        assertThat(capped.stream().filter(r -> r.get("source_type").equals("TEST"))
                .map(r -> (Long) r.get("sequence_id")).toList()).isSorted();
    }

    private void drainObservations() throws Exception {
        flushSse();
        var writer = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(sseRecorder, "writer");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> writer.getQueue().remainingCapacity() > 0);
        writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private void reject(String type) {
        jdbc.execute("ALTER TABLE waiting_event DROP CONSTRAINT IF EXISTS test_reject_event");
        jdbc.execute("ALTER TABLE waiting_event ADD CONSTRAINT test_reject_event CHECK(event_type <> '" + type + "') NOT VALID");
    }

    private void race(Runnable task) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); task.run(); return true; });
            var b = pool.submit(() -> { start.await(); task.run(); return true; });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void append(WaitingEventReference ref) {
        transactions.executeWithoutResult(tx -> {
            eventRepository.append(ref, "TEST", START, "WAITING", waitingId.toString(), Map.of());
            assertThat(tx.isRollbackOnly()).isFalse();
        });
    }

    private Member member(UserType type) {
        return members.save(Member.builder().name("test").phone("01000000000").userType(type).build());
    }

    private List<String> types() {
        return jdbc.queryForList("select event_type from waiting_event where waiting_id=? and source_type in ('WAITING','WAITING_END_HISTORY','WAITING_DEFER_HISTORY') order by occurred_at,id", String.class, waitingId);
    }

    private List<String> alimtalkTypes() {
        return jdbc.queryForList("select event_type from waiting_event where waiting_id=? and source_type='ALIMTALK_OUTBOX' order by occurred_at,id", String.class, waitingId);
    }

    private List<UUID> outboxIds() {
        return jdbc.queryForList("select id from alimtalk_outbox where waiting_id=? order by call_count,recall_count", UUID.class, waitingId);
    }

    private JsonNode data(String type) throws Exception {
        return mapper.readTree(jdbc.queryForObject("select details::text from waiting_event where waiting_id=? and event_type=? order by id limit 1", String.class, waitingId, type));
    }

    private JsonNode lastCallObservation() throws Exception {
        flushSse();
        return mapper.readTree(jdbc.queryForObject("""
                SELECT jsonb_build_object('connections',count(*) FILTER(WHERE t.connection_generation<>'NONE'),
                  'attempted',count(a.id),'serverSendSucceeded',count(*) FILTER(WHERE a.status='SEND_RETURNED'),
                  'serverSendFailed',count(*) FILTER(WHERE t.status='FAILED'),'customerReceipt','UNKNOWN',
                  'businessEventKey',split_part(r.event_key,':direct:',1))::text
                FROM waiting_sse_root r JOIN waiting_sse_target t ON t.root_id=r.id
                LEFT JOIN waiting_sse_attempt a ON a.target_id=t.id
                WHERE r.waiting_id=? AND r.event_type='WAITING_CALLED'
                GROUP BY r.id ORDER BY r.created_at DESC LIMIT 1
                """, String.class,waitingId));
    }

    private CustomerNotificationEvent callEvent() {
        var w = waitings.findById(waitingId).orElseThrow();
        return WaitingCallNotification.of(customer.getId(), waitingId, w.getWaitNumber(), store.getName(), w.getCalledAt(), w.getCallExpiresAt());
    }
}
