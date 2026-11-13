package com.catchtable.notification.durability;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.notification.service.NotificationService;
import com.catchtable.payment.ReconcileTestClock;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.service.OwnerWaitingService;
import com.catchtable.waiting.service.WaitingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2", "waiting.expiry-interval-ms=3600000"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Import(ReconcileTestClock.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WaitingSseDurabilityTest {
    @Autowired WaitingSseJournal journal;
    @Autowired WaitingSseWorker worker;
    @Autowired WaitingSseProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired ReconcileTestClock clock;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired SseEmitterRepository emitters;
    @Autowired NotificationService notifications;
    @Autowired com.catchtable.notification.service.NotificationSettingService settings;
    @Autowired OwnerWaitingService ownerService;
    @Autowired WaitingService service;
    @Autowired MemberRepository members;
    @Autowired StoreRepository stores;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("waitingSseScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler waitingScheduler;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("taskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler commonScheduler;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("paymentTaskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler paymentScheduler;
    Member owner,customer;
    Store store;
    Long waiting;
    final OffsetDateTime start=OffsetDateTime.parse("2030-01-20T12:00:00+09:00");

    @BeforeEach void setup() {
        clearJournal(); clock.set(start);
        owner=members.save(Member.builder().name("SSE-owner").phone("01000000000").userType(UserType.OWNER).build());
        customer=members.save(Member.builder().name("SSE-customer").phone("01000000000").userType(UserType.CUSTOMER).build());
        store=stores.save(Store.builder().owner(owner).name("SSE-test").address("test").category(StoreCategory.KOREAN)
                .reservationDurationMinutes(90).reservationSlotMinutes(30).arrivalGraceMinutes(10).build());
        ownerService.openWaiting(owner.getId(),store.getId());
        WaitingBusinessHourFixture.openNow(jdbc,clock,store.getId());
        waiting=service.createWaiting(customer.getId(),store.getId(),2).response().waitingId();
        drain(); clearJournal();
    }
    @AfterEach void cleanup() {
        jdbc.execute("ALTER TABLE waiting_sse_observation DROP CONSTRAINT IF EXISTS test_reject_sse");
        jdbc.execute("ALTER TABLE waiting_sse_root DROP CONSTRAINT IF EXISTS test_reject_root");
        List.copyOf(emitters.findAllByMemberId(customer.getId())).forEach(e->emitters.delete(customer.getId(),e));
        List.copyOf(emitters.findAllByMemberId(owner.getId())).forEach(e->emitters.delete(owner.getId(),e));
        clearJournal();
        jdbc.update("DELETE FROM waiting_end_history WHERE waiting_id=?",waiting);
        jdbc.update("DELETE FROM waiting WHERE store_id=?",store.getId());
        WaitingBusinessHourFixture.clear(jdbc,store.getId()); stores.delete(store); members.deleteAll(List.of(owner,customer));
    }
    void clearJournal() {
        jdbc.execute("TRUNCATE waiting_sse_observation,waiting_sse_attempt,waiting_sse_target,waiting_sse_root");
        jdbc.update("UPDATE waiting_sse_capacity SET retained_roots=0");
    }
    void drain() {
        WaitingSseTestDrain.forStore(jdbc,worker,store.getId()).done();
    }
    long count(String table,String where) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+where,Long.class); }
    CustomerNotificationEvent event() { return new CustomerNotificationEvent(customer.getId(),NotificationType.WAITING_CANCELED,waiting,"test"); }
    void enqueue(CustomerNotificationEvent event) { tx.executeWithoutResult(s->publisher.publishEvent(event)); }
    SseEmitter emitter() { var e=mock(SseEmitter.class); emitters.save(customer.getId(),e); return e; }
    void expireLease() { jdbc.update("UPDATE waiting_sse_root SET lease_until=clock_timestamp()-interval '1 second' WHERE status='CLAIMED'"); }

    @Test void rootFailureRollsBackBusinessHistoryAndAllRoots() {
        jdbc.execute("ALTER TABLE waiting_sse_root ADD CONSTRAINT test_reject_root CHECK(event_type <> 'WAITING_CALLED') NOT VALID");
        assertThatThrownBy(()->ownerService.callWaiting(owner.getId(),waiting)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting WHERE id=?",String.class,waiting)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_event WHERE waiting_id=? AND event_type='CALLED'",Long.class,waiting)).isZero();
        assertThat(count("waiting_sse_root","true")).isZero();
        assertThat(jdbc.queryForObject("SELECT retained_roots FROM waiting_sse_capacity",Long.class)).isZero();
    }
    @Test void rollbackRemovesRootsAndDuplicateListenersHaveOneRoot() throws Exception {
        var e=emitter(); var event=event();
        tx.executeWithoutResult(s->{publisher.publishEvent(event); s.setRollbackOnly();});
        assertThat(count("waiting_sse_root","true")).isZero();
        tx.executeWithoutResult(s->{publisher.publishEvent(event); notifications.send(event); publisher.publishEvent(event);});
        assertThat(count("waiting_sse_root","true")).isEqualTo(1);
        verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
        drain(); verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_root","status<>'DONE'")).isZero();
    }
    @Test void outsideTransactionHasNoFallback() throws Exception {
        var e=emitter();
        assertThatThrownBy(()->publisher.publishEvent(event())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->notifications.send(event())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher.publishEvent(new WaitingQueueChangedEvent(store.getId(),start.toLocalDate()))).isInstanceOf(RuntimeException.class);
        verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void premarkWriteFailureSendsNothingAndNeverAttemptedCanRecover() throws Exception {
        var e=emitter(); enqueue(event());
        jdbc.execute("ALTER TABLE waiting_sse_observation ADD CONSTRAINT test_reject_sse CHECK(phase<>'MARKED') NOT VALID");
        worker.runOnce();
        verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_attempt","true")).isZero();
        jdbc.execute("ALTER TABLE waiting_sse_observation DROP CONSTRAINT test_reject_sse");
        expireLease(); worker.runOnce();
        verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_root","status='DONE'")).isEqualTo(1);
    }
    @Test void multipleConnectionsPartialFailuresAndNoCustomerReceiptClaim() throws Exception {
        var ok=emitter();var io=emitter();var state=emitter();
        doThrow(new IOException("closed")).when(io).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("closed")).when(state).send(any(SseEmitter.SseEventBuilder.class));
        enqueue(event()); drain();
        assertThat(count("waiting_sse_target","status='SENT'")).isEqualTo(1);
        assertThat(count("waiting_sse_target","status='FAILED'")).isEqualTo(2);
        assertThat(count("waiting_sse_attempt","customer_receipt<>'UNKNOWN'")).isZero();
        assertThat(count("waiting_sse_root","status='DONE' AND expected_targets=3 AND expected_recipients=1")).isEqualTo(1);
        worker.runOnce(); verify(ok,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void emptyConnectionsAndEmptyQueueAreDistinctAndReconnectDoesNotReplay() throws Exception {
        enqueue(event());
        tx.executeWithoutResult(s->publisher.publishEvent(new WaitingQueueChangedEvent(store.getId(),start.toLocalDate().minusDays(1))));
        drain();
        assertThat(count("waiting_sse_target","reason='NO_CONNECTION'")).isEqualTo(1);
        assertThat(count("waiting_sse_root","expanded_at IS NOT NULL AND expected_targets=0 AND expected_recipients=0")).isEqualTo(1);
        var e=emitter(); worker.runOnce();verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void processingSettingsExpirySupersessionAndInactiveAreRecorded() {
        ownerService.callWaiting(owner.getId(),waiting);
        ownerService.callWaiting(owner.getId(),waiting);
        clock.set(start.plusMinutes(11)); drain();
        assertThat(count("waiting_sse_target","reason='SUPERSEDED_CALL'")).isEqualTo(1);
        assertThat(count("waiting_sse_target","reason='CALL_EXPIRED'")).isEqualTo(1);
        clearJournal();clock.set(start);
        tx.executeWithoutResult(s->publisher.publishEvent(new WaitingQueueChangedEvent(store.getId(),start.toLocalDate())));
        jdbc.update("INSERT INTO member_notification_setting(member_id,waiting_enabled,reservation_enabled,created_at,updated_at) VALUES(?,false,true,now(),now())",customer.getId());
        drain();assertThat(count("waiting_sse_target","reason='SETTING_DISABLED'")).isEqualTo(1);
        clearJournal();ownerService.callWaiting(owner.getId(),waiting);
        jdbc.update("UPDATE waiting SET status='SEATED' WHERE id=?",waiting);drain();
        assertThat(count("waiting_sse_target","reason='WAITING_INACTIVE'")).isEqualTo(1);
    }
    @Test void changedSettingsAndConnectionGenerationAreRecheckedBeforeMark() {
        var e=emitter();
        enqueue(new CustomerNotificationEvent(customer.getId(),NotificationType.WAITING_POSITION_CHANGED,waiting,null));
        var c=journal.claim();assertThat(journal.expand(c)).isTrue();
        var t=journal.ready(c).getFirst();
        jdbc.update("INSERT INTO member_notification_setting(member_id,waiting_enabled,reservation_enabled,created_at,updated_at) VALUES(?,false,true,now(),now())",customer.getId());
        assertThat(journal.mark(c,t)).isNull();journal.finish(c);
        assertThat(count("waiting_sse_target","reason='SETTING_DISABLED'")).isEqualTo(1);
        clearJournal();enqueue(event());c=journal.claim();journal.expand(c);t=journal.ready(c).getFirst();
        emitters.delete(customer.getId(),e);emitters.save(customer.getId(),e);
        assertThat(journal.mark(c,t)).isNull();journal.finish(c);
        assertThat(count("waiting_sse_target","reason='CONNECTION_GONE'")).isEqualTo(1);
    }
    @Test void leaseRecoveryAndAliveStaleWorkerNeverReplayMarkedTarget() throws Exception {
        var e=emitter();enqueue(event());var c=journal.claim();journal.expand(c);var target=journal.ready(c).getFirst();
        var a=journal.mark(c,target);assertThat(a).isNotNull();
        expireLease();var recovered=journal.claim();
        assertThat(journal.mark(c,target)).isNull();assertThat(journal.authorized(a)).isFalse();
        journal.expand(recovered);journal.finish(recovered);worker.runOnce();
        verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_root","status='UNKNOWN'")).isEqualTo(1);
        assertThat(count("waiting_sse_observation","phase='UNKNOWN' AND last_confirmed_stage='ATTEMPT_MARK_COMMITTED'")).isEqualTo(1);
        var result=new WaitingSseJournal.Result(a.id(),"SEND_RETURNED",start);
        journal.saveResult(result);journal.saveResult(result);
        assertThat(count("waiting_sse_observation","phase='UNKNOWN'")).isEqualTo(1);
        assertThat(count("waiting_sse_observation","phase='SEND_RETURNED'")).isEqualTo(1);
        assertThat(count("waiting_sse_root","status='DONE'")).isEqualTo(1);
    }
    @Test void partialFanoutRecoveryContinuesOnlyNeverAttemptedTargets() throws Exception {
        emitter();emitter();emitter();enqueue(event());var c=journal.claim();journal.expand(c);var targets=journal.ready(c);
        var done=journal.mark(c,targets.get(0));journal.saveResult(new WaitingSseJournal.Result(done.id(),"SEND_RETURNED",start));
        journal.mark(c,targets.get(1));expireLease();worker.runOnce();
        assertThat(count("waiting_sse_attempt","true")).isEqualTo(3);
        assertThat(count("waiting_sse_target","status='SENT'")).isEqualTo(2);
        assertThat(count("waiting_sse_target","status='UNKNOWN'")).isEqualTo(1);
        long sends=emitters.findAllByMemberId(customer.getId()).stream().mapToLong(e->mockingDetails(e).getInvocations().size()).sum();
        assertThat(sends).isEqualTo(1);
    }
    @Test void resultCommitFailureLeavesBusinessCommittedAndUnknownWithoutReplay() throws Exception {
        var e=emitter();enqueue(event());
        jdbc.execute("ALTER TABLE waiting_sse_observation ADD CONSTRAINT test_reject_sse CHECK(phase<>'SEND_RETURNED') NOT VALID");
        worker.runOnce();verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_attempt","status='MARKED'")).isEqualTo(1);
        jdbc.execute("ALTER TABLE waiting_sse_observation DROP CONSTRAINT test_reject_sse");
        expireLease();worker.runOnce();worker.runOnce();
        verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_attempt","status='UNKNOWN'")).isEqualTo(1);
    }
    @Test void committedMarkResponseLossDoesNotSendAndCommittedResultResponseLossDoesNotResend() throws Exception {
        var e=emitter();enqueue(event());
        var proxy=spy(new WaitingSseJournal(new com.catchtable.notification.repository.WaitingSseJournalRepository(jdbc),mapper,clock,properties,settings,emitters,manager));
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("test: committed mark response lost");}).when(proxy).mark(any(),any());
        new WaitingSseWorker(proxy,properties,emitters,mapper,clock).runOnce();
        verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
        expireLease();worker.runOnce();assertThat(count("waiting_sse_attempt","status='UNKNOWN'")).isEqualTo(1);
        clearJournal();enqueue(event());
        var resultProxy=spy(new WaitingSseJournal(new com.catchtable.notification.repository.WaitingSseJournalRepository(jdbc),mapper,clock,properties,settings,emitters,manager));
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("test: committed result response lost");}).when(resultProxy).saveResult(any());
        new WaitingSseWorker(resultProxy,properties,emitters,mapper,clock).runOnce();
        worker.runOnce();verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(count("waiting_sse_attempt","status='SEND_RETURNED'")).isEqualTo(1);
        assertThat(count("waiting_sse_observation","phase='SEND_RETURNED'")).isEqualTo(1);
    }
    @Test void capacityRejectsWholeBusinessAndConcurrentAdmissionsCannotOversubscribe() throws Exception {
        jdbc.update("UPDATE waiting_sse_capacity SET retained_roots=?",properties.maxRetainedRoots()-1);
        assertThatThrownBy(()->ownerService.callWaiting(owner.getId(),waiting)).isInstanceOf(WaitingSseCapacityException.class);
        assertThat(jdbc.queryForObject("SELECT call_count FROM waiting WHERE id=?",Integer.class,waiting)).isZero();
        assertThat(count("waiting_sse_root","true")).isZero();
        assertThat(jdbc.queryForObject("SELECT retained_roots FROM waiting_sse_capacity",Long.class)).isEqualTo(properties.maxRetainedRoots()-1);
        clearJournal();
        var limits=new WaitingSseProperties(true,false,1,100,16384,30);
        var small=new WaitingSseJournal(new com.catchtable.notification.repository.WaitingSseJournalRepository(jdbc),mapper,clock,limits,settings,emitters,manager);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(n->pool.submit(()->{
                gate.await();try{tx.executeWithoutResult(s->small.enqueue(event()));return 1;}catch(WaitingSseCapacityException ex){return 0;}
            })).toList();gate.countDown();
            assertThat(tasks.get(0).get(5,TimeUnit.SECONDS)+tasks.get(1).get(5,TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(count("waiting_sse_root","true")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT retained_roots FROM waiting_sse_capacity",Long.class)).isEqualTo(1);
    }
    @Test void pausedDeliveryKeepsPendingAndExpansionLimitDoesNotLoseRecipients() {
        emitter();emitter();enqueue(event());
        var paused=new WaitingSseProperties(false,false,100,1,16384,30);
        new WaitingSseWorker(journal,paused,emitters,mapper,clock).runOnce();
        assertThat(count("waiting_sse_root","status='PENDING' AND expected_targets IS NULL")).isEqualTo(1);
        var limited=new WaitingSseJournal(new com.catchtable.notification.repository.WaitingSseJournalRepository(jdbc),mapper,clock,paused,settings,emitters,manager);
        var c=limited.claim();assertThat(limited.expand(c)).isFalse();
        assertThat(count("waiting_sse_target","true")).isZero();
        assertThat(count("waiting_sse_observation","reason='TARGET_LIMIT'")).isEqualTo(1);
        expireLease();worker.runOnce();assertThat(count("waiting_sse_target","status='SENT'")).isEqualTo(2);
    }
    @Test @Timeout(15) void slowSendHoldsNoDatabaseConnectionWhileConcurrentBusinessProgresses() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var e=emitter();
        doAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Thread.currentThread().getName()).startsWith("waiting-sse-");
            assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
            entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return null;
        }).when(e).send(any(SseEmitter.SseEventBuilder.class));
        enqueue(event());
        try(var pool=Executors.newFixedThreadPool(3)) {
            var work=waitingScheduler.submit(worker::runOnce);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                var first=pool.submit(()->ownerService.callWaiting(owner.getId(),waiting));
                var common=commonScheduler.submit(()->jdbc.queryForObject("SELECT count(*) FROM reservation",Long.class));
                var payment=paymentScheduler.submit(()->jdbc.queryForObject("SELECT count(*) FROM payment",Long.class));
                assertThat(payment.get(3,TimeUnit.SECONDS)).isNotNull();
                assertThat(first.get(3,TimeUnit.SECONDS)).isNotNull();assertThat(common.get(3,TimeUnit.SECONDS)).isNotNull();
                worker.runOnce(); // Concurrent invocation is bounded, without queuing.
            } finally {release.countDown();}
            work.get(3,TimeUnit.SECONDS);
        }
        verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void duplicateClaimAndDuplicateMarkAreSerializedInPostgresql() throws Exception {
        emitter();enqueue(event());var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return journal.claim();});
            var two=pool.submit(()->{gate.await();return journal.claim();});gate.countDown();
            var claims=java.util.stream.Stream.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).toList();
            assertThat(claims).hasSize(1);var c=claims.getFirst();journal.expand(c);var t=journal.ready(c).getFirst();
            var a=pool.submit(()->journal.mark(c,t));var b=pool.submit(()->journal.mark(c,t));
            assertThat(java.util.stream.Stream.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        }
        assertThat(count("waiting_sse_attempt","true")).isEqualTo(1);
    }
    @Test void expansionCommitResponseLossKeepsSameSnapshotAndNeverAddsReconnectedTargets() throws Exception {
        var old=emitter();enqueue(event());var c=journal.claim();
        assertThat(journal.expand(c)).isTrue(); // The real PostgreSQL commit succeeded, caller then loses its response.
        var original=journal.ready(c).getFirst();var reconnected=emitter();
        expireLease();worker.runOnce();
        verify(old,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(reconnected,never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(jdbc.queryForObject("SELECT id FROM waiting_sse_target",UUID.class)).isEqualTo(original.id());
    }
    @Test void unexpectedSendExceptionIsUnknownAndCannotReplay() throws Exception {
        var e=emitter();doThrow(new UnsupportedOperationException("test")).when(e).send(any(SseEmitter.SseEventBuilder.class));
        enqueue(event());
        UUID root=jdbc.queryForObject("SELECT id FROM waiting_sse_root WHERE store_id=?",UUID.class,store.getId());
        WaitingSseTestDrain.forStore(jdbc,worker,store.getId()).expecting(java.util.Map.of(root,"UNKNOWN"));
        worker.runOnce();
        assertThat(count("waiting_sse_target","status='UNKNOWN' AND reason='RUNTIME_ERROR'")).isEqualTo(1);
        assertThat(count("waiting_sse_attempt","status='RUNTIME_ERROR' AND customer_receipt='UNKNOWN'")).isEqualTo(1);
        verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void operatorTimelineRequiresMatchingBusinessAndStoreAndOmitsPayload() throws Exception {
        enqueue(event());drain();
        String query=java.nio.file.Files.readString(java.nio.file.Path.of("scripts/waiting-sse-journal.sql"))
                .replace("$1","?").replace("$2","?").replace("$3","?");
        var rows=jdbc.queryForList(query,waiting,store.getId(),store.getId(),500);
        assertThat(rows).isNotEmpty();assertThat(rows.toString()).doesNotContain("01000000000","SSE-test","\"message\"");
        assertThat(jdbc.queryForList(query,waiting,-1L,-1L,500)).isEmpty();
        assertThat(jdbc.queryForList(query,waiting,store.getId(),store.getId(),1)).hasSize(1);
    }
    @Test void requiredCallsIgnoreDisabledOptionalSettingsAndKeepOriginalDeadline() throws Exception {
        var e=emitter();ownerService.callWaiting(owner.getId(),waiting);
        var expiry=jdbc.queryForObject("SELECT call_expires_at FROM waiting WHERE id=?",OffsetDateTime.class,waiting);
        jdbc.update("INSERT INTO member_notification_setting(member_id,waiting_enabled,reservation_enabled,created_at,updated_at) VALUES(?,false,true,now(),now())",customer.getId());
        drain();assertThat(count("waiting_sse_attempt","status='SEND_RETURNED'")).isEqualTo(1);
        assertThat(count("waiting_sse_target","reason='SETTING_DISABLED'")).isEqualTo(1);
        ownerService.recallWaiting(owner.getId(),waiting);drain();
        assertThat(jdbc.queryForObject("SELECT call_expires_at FROM waiting WHERE id=?",OffsetDateTime.class,waiting)).isEqualTo(expiry);
        verify(e,times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void capacityResponseIs503WithRetryAfterAndNoAcceptedBusinessChange() throws Exception {
        jdbc.update("UPDATE waiting_sse_capacity SET retained_roots=?",properties.maxRetainedRoots());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/owner/waitings/{id}/call",waiting)
                .header("X-Member-Id",owner.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After","30"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("WAITING_SSE_CAPACITY_EXCEEDED"));
        assertThat(jdbc.queryForObject("SELECT call_count FROM waiting WHERE id=?",Integer.class,waiting)).isZero();
    }

    @Test void raisingRetainedRootLimitRestoresAdmissionWithoutDeletingHistory() {
        var small=new WaitingSseJournal(new com.catchtable.notification.repository.WaitingSseJournalRepository(jdbc),mapper,clock,new WaitingSseProperties(true,false,1,100,16384,30),settings,emitters,manager);
        var first=event();tx.executeWithoutResult(s->small.enqueue(first));drain();
        assertThatThrownBy(()->tx.executeWithoutResult(s->small.enqueue(event()))).isInstanceOf(WaitingSseCapacityException.class);
        var larger=new WaitingSseJournal(new com.catchtable.notification.repository.WaitingSseJournalRepository(jdbc),mapper,clock,new WaitingSseProperties(true,false,2,100,16384,30),settings,emitters,manager);
        tx.executeWithoutResult(s->larger.enqueue(event()));drain();
        assertThat(count("waiting_sse_root","status='DONE'")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT retained_roots FROM waiting_sse_capacity",Long.class)).isEqualTo(2);
        assertThat(count("waiting_sse_observation","phase='ACCEPTED'")).isEqualTo(2);
        tx.executeWithoutResult(s->small.enqueue(first)); // Duplicate is accepted even when a lower limit is full.
        assertThat(count("waiting_sse_root","true")).isEqualTo(2);
    }

    @Test void scheduledBurstCompletesSeveralRootsWithSeparateCommittedAttempts() throws Exception {
        var e = emitter();
        doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_attempt WHERE status='MARKED'", Long.class)).isEqualTo(1);
            return null;
        }).when(e).send(any(SseEmitter.SseEventBuilder.class));
        for (int i = 0; i < 5; i++) enqueue(event());
        var burst = new WaitingSseWorker(journal, new WaitingSseProperties(true,true,1000,100,16384,30,3,10000), emitters, mapper, clock);
        burst.scheduled();
        assertThat(count("waiting_sse_root", "status='DONE'")).isEqualTo(3);
        assertThat(count("waiting_sse_root", "status='PENDING'")).isEqualTo(2);
        assertThat(count("waiting_sse_attempt", "status='SEND_RETURNED'")).isEqualTo(3);
        burst.scheduled();
        WaitingSseTestDrain.forStore(jdbc,worker,store.getId()).assertStates(jdbc.queryForList("SELECT id FROM waiting_sse_root",java.util.UUID.class)
                .stream().collect(java.util.stream.Collectors.toMap(id->id,id->"DONE")));
        assertThat(count("waiting_sse_observation", "phase='SEND_RETURNED'")).isEqualTo(5);
        verify(e,times(5)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void burstDefersAfterResultWriteFailureAndRecoveryNeverReplaysUnknown() throws Exception {
        var e = emitter(); enqueue(event()); enqueue(event());
        var burst = new WaitingSseWorker(journal, new WaitingSseProperties(true,true,1000,100,16384,30,64,10000), emitters, mapper, clock);
        jdbc.execute("ALTER TABLE waiting_sse_observation ADD CONSTRAINT test_reject_sse CHECK(phase<>'SEND_RETURNED') NOT VALID");
        burst.scheduled();
        assertThat(count("waiting_sse_root", "status='PENDING'")).isEqualTo(1);
        assertThat(count("waiting_sse_attempt", "status='MARKED'")).isEqualTo(1);
        var unknownRoot = jdbc.queryForObject("SELECT id FROM waiting_sse_root WHERE status='CLAIMED'", java.util.UUID.class);
        verify(e).send(any(SseEmitter.SseEventBuilder.class));
        jdbc.execute("ALTER TABLE waiting_sse_observation DROP CONSTRAINT test_reject_sse");
        expireLease(); burst.scheduled();
        WaitingSseTestDrain.forStore(jdbc,worker,store.getId()).expecting(java.util.Map.of(unknownRoot,"UNKNOWN"));
        assertThat(count("waiting_sse_attempt", "status='UNKNOWN'")).isEqualTo(1);
        assertThat(count("waiting_sse_attempt", "status='SEND_RETURNED'")).isEqualTo(1);
        verify(e,times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void drainRejectsClaimedResidualAndAcceptsOnlyExplicitExpectedRoot() {
        enqueue(event());var claim=journal.claim();
        var drain=WaitingSseTestDrain.forRoots(jdbc,worker,java.util.Set.of(claim.root().id()));
        assertThatThrownBy(drain::done).isInstanceOf(AssertionError.class).hasMessageContaining("CLAIMED");
        drain.expecting(java.util.Map.of(claim.root().id(),"CLAIMED"));
    }
    @Test void drainFailsWhenRunBudgetIsExhaustedAndAllowsDeclaredPending() {
        enqueue(event());UUID root=jdbc.queryForObject("SELECT id FROM waiting_sse_root",UUID.class);
        var paused=new WaitingSseWorker(journal,new WaitingSseProperties(false,false,100,100,16384,30),emitters,mapper,clock);
        var drain=WaitingSseTestDrain.forRoots(jdbc,paused,java.util.Set.of(root));
        assertThatThrownBy(()->drain.expecting(java.util.Map.of(),2)).isInstanceOf(AssertionError.class).hasMessageContaining("PENDING");
        drain.expecting(java.util.Map.of(root,"PENDING"),0);
    }
    @Test void drainRejectsUnexpectedUnknownInsteadOfTreatingItAsCompletion() throws Exception {
        var e=emitter();doThrow(new UnsupportedOperationException("test")).when(e).send(any(SseEmitter.SseEventBuilder.class));
        enqueue(event());UUID root=jdbc.queryForObject("SELECT id FROM waiting_sse_root",UUID.class);
        var drain=WaitingSseTestDrain.forRoots(jdbc,worker,java.util.Set.of(root));
        assertThatThrownBy(drain::done).isInstanceOf(AssertionError.class).hasMessageContaining("UNKNOWN");
        drain.expecting(java.util.Map.of(root,"UNKNOWN"));
        verify(e,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void drainObservesUncommittedPendingWithoutSendingAndRollbackRemovesIt() throws Exception {
        var e=emitter();
        tx.executeWithoutResult(s->{
            publisher.publishEvent(event());UUID root=jdbc.queryForObject("SELECT id FROM waiting_sse_root",UUID.class);
            var drain=WaitingSseTestDrain.forRoots(jdbc,worker,java.util.Set.of(root));
            drain.assertStates(java.util.Map.of(root,"PENDING"));
            assertThatThrownBy(drain::done).isInstanceOf(AssertionError.class).hasMessageContaining("business TX");
            assertThat(count("waiting_sse_attempt","true")).isZero();s.setRollbackOnly();
        });
        drain();assertThat(count("waiting_sse_root","true")).isZero();
        verify(e,never()).send(any(SseEmitter.SseEventBuilder.class));
    }

}
