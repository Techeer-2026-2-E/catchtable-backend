package com.catchtable.notification.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.client.AlimtalkGateway;
import com.catchtable.notification.client.AlimtalkGateway.Outcome;
import com.catchtable.notification.client.AlimtalkGateway.Result;
import com.catchtable.notification.dto.AlimtalkDelivery;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.AlimtalkOutboxRepository;
import com.catchtable.payment.ReconcileTestClock;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingCallNotification;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import com.catchtable.waiting.service.OwnerWaitingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"alimtalk.enabled=true", "alimtalk.initial-delay-ms=3600000",
        "waiting.expiry-interval-ms=3600000", "alimtalk.mode=solapi",
        "alimtalk.solapi.api-key=test-key", "alimtalk.solapi.api-secret=test-secret",
        "alimtalk.solapi.channel-id=test-channel", "alimtalk.solapi.template-id=test-template",
        "alimtalk.solapi.base-url=http://127.0.0.1:1", "alimtalk.solapi.webhook-secret=test-webhook"})
@AutoConfigureMockMvc
@Import(ReconcileTestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlimtalkDeliveryTest {
    @Autowired OwnerWaitingService service;
    @Autowired AlimtalkDeliveryService dispatcher;
    @Autowired AlimtalkOutboxRepository outbox;
    @Autowired MemberRepository members;
    @Autowired StoreRepository stores;
    @Autowired WaitingRepository waitings;
    @Autowired WaitingEndHistoryRepository histories;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    @Autowired ReconcileTestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean AlimtalkGateway gateway;
    private static final OffsetDateTime START = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");
    private Member owner;
    private Member customer;
    private Store store;
    private Waiting waiting;

    @BeforeEach
    void prepare() {
        clock.set(START);
        owner = members.save(Member.builder().name("점주").phone("010-0000-0001")
                .userType(UserType.OWNER).build());
        customer = members.save(Member.builder().name("고객").phone("010-0000-0002")
                .userType(UserType.CUSTOMER).build());
        store = stores.save(Store.builder().owner(owner).name("알림 테스트 매장")
                .category(StoreCategory.values()[0]).address("서울")
                .reservationDurationMinutes(90).reservationSlotMinutes(30).arrivalGraceMinutes(10).build());
        waiting = waitings.save(Waiting.create(customer, store, LocalDate.now(clock), 2, 1));
    }

    @AfterEach
    void clean() {
        histories.deleteAllInBatch();
        waitings.deleteAllInBatch();
        jdbc.update("delete from store_table where store_id = ?", store.getId());
        stores.delete(store);
        members.deleteAll(List.of(customer, owner));
    }

    @Test
    void callAndRecallCaptureDistinctRequestsWithOriginalExpiryAndRequiredPolicy() {
        jdbc.update("INSERT INTO member_notification_setting VALUES (?, false, false, ?, ?)",
                customer.getId(), START, START);
        service.callWaiting(owner.getId(), waiting.getId());
        var expiry = waitings.findById(waiting.getId()).orElseThrow().getCallExpiresAt();
        clock.set(START.plusSeconds(10));
        service.recallWaiting(owner.getId(), waiting.getId());
        var rows = deliveries();
        assertThat(rows).hasSize(2).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo("PENDING");
            assertThat(d.expiresAt().toInstant()).isEqualTo(expiry.toInstant());
            assertThat(d.attemptCount()).isZero();
        });
        assertThat(rows).extracting(AlimtalkDelivery::recallCount).containsExactlyInAnyOrder(0, 1);
        verifyNoInteractions(gateway);
    }

    @Test
    void repeatedEventInSameCallDoesNotCreateAnotherRequest() {
        transactions.executeWithoutResult(tx -> {
            service.callWaiting(owner.getId(), waiting.getId());
            publishCall();
            publishCall();
        });
        assertThat(deliveries()).hasSize(1);
    }

    @Test
    void rollbackRemovesRequestAndBusinessChange() {
        transactions.executeWithoutResult(tx -> {
            service.callWaiting(owner.getId(), waiting.getId());
            assertThat(deliveries()).hasSize(1);
            tx.setRollbackOnly();
        });
        assertThat(deliveries()).isEmpty();
        assertThat(waitings.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.WAITING);
        dispatcher.poll();
        verifyNoInteractions(gateway);
    }

    @Test
    void otherEventsDoNotCreateAlimtalkRequests() {
        transactions.executeWithoutResult(tx -> events.publishEvent(new CustomerNotificationEvent(
                customer.getId(), NotificationType.RESERVATION_CONFIRMED, waiting.getId(), "예약 확정")));
        assertThat(deliveries()).isEmpty();
    }

    @Test
    void gatewayRunsAfterRequestCommitWithoutOuterTransaction() {
        var delivery = call();
        when(gateway.send(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(outbox.find(delivery.id()).orElseThrow().status()).isEqualTo("PROCESSING");
            return new Result(Outcome.DELIVERED, "m1", null);
        });
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        verify(gateway).send(argThat(r -> r.recipient().equals("01000000002")));
    }

    @Test
    void confirmedTransientFailureRetriesAtThirtySecondsThenTwoMinutesAndStopsAtThree() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.RETRYABLE_FAILURE, null, "TEMPORARY"));
        dispatcher.poll();
        assertThat(latest(delivery).nextAttemptAt().toInstant()).isEqualTo(START.plusSeconds(30).toInstant());
        clock.set(START.plusSeconds(29));
        dispatcher.poll();
        verify(gateway, times(1)).send(any());
        clock.set(START.plusSeconds(30));
        dispatcher.poll();
        assertThat(latest(delivery).nextAttemptAt().toInstant()).isEqualTo(START.plusSeconds(150).toInstant());
        clock.set(START.plusSeconds(149));
        dispatcher.poll();
        verify(gateway, times(2)).send(any());
        clock.set(START.plusSeconds(150));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("FAILED");
        assertThat(latest(delivery).attemptCount()).isEqualTo(3);
        clock.set(START.plusSeconds(200));
        dispatcher.poll();
        verify(gateway, times(3)).send(any());
    }

    @Test
    void lostSendResponseIsUnknownAndNeverResentOnRestart() {
        var delivery = call();
        when(gateway.send(any())).thenThrow(new IllegalStateException("response lost"));
        dispatcher.poll();
        clock.set(START.plusSeconds(120));
        var restarted = new AlimtalkDeliveryService(outbox, gateway, clock);
        ReflectionTestUtils.setField(restarted, "mode", "solapi");
        restarted.poll();
        assertThat(latest(delivery).status()).isEqualTo("UNKNOWN");
        assertThat(latest(delivery).attemptCount()).isEqualTo(1);
        verify(gateway, times(1)).send(any());
    }

    @Test
    void interruptedProcessingBecomesUnknownWithoutAnotherSend() {
        var delivery = call();
        outbox.claimSend(delivery.id(), START).orElseThrow();
        clock.set(START.plusSeconds(61));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("UNKNOWN");
        assertThat(latest(delivery).lastErrorCode()).isEqualTo("PROCESS_INTERRUPTED");
        verifyNoInteractions(gateway);
    }

    @Test
    void acceptanceIsNotDeliveryAndLookupCompletesTheSameAttempt() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.ACCEPTED, "m1", null));
        when(gateway.lookup(any(), eq("m1"))).thenReturn(new Result(Outcome.DELIVERED, "m1", null));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("ACCEPTED");
        verify(gateway, never()).lookup(any(), any());
        clock.set(START.plusSeconds(30));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        assertThat(latest(delivery).attemptCount()).isEqualTo(1);
        verify(gateway, times(1)).send(any());
    }

    @Test
    void failedLookupKeepsCorrelationAndDoesNotResend() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.ACCEPTED, "m1", null));
        when(gateway.lookup(any(), eq("m1"))).thenThrow(new IllegalStateException("read timeout"))
                .thenReturn(new Result(Outcome.DELIVERED, "m1", null));
        dispatcher.poll();
        clock.set(START.plusSeconds(30));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("UNKNOWN");
        assertThat(latest(delivery).providerMessageId()).isEqualTo("m1");
        clock.set(START.plusSeconds(60));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void expiryAtExactDeadlineSkipsPendingDelivery() {
        var delivery = call();
        clock.set(delivery.expiresAt());
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SKIPPED");
        verifyNoInteractions(gateway);
    }

    @Test
    void cancellationSkipsPendingDelivery() {
        var delivery = call();
        service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정");
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SKIPPED");
        verifyNoInteractions(gateway);
    }

    @Test
    void seatingSkipsPendingDelivery() {
        var delivery = call();
        Long tableId = jdbc.queryForObject(
                "INSERT INTO store_table (store_id, table_number, capacity) VALUES (?, 1, 4) RETURNING id",
                Long.class, store.getId());
        service.enterWaiting(owner.getId(), waiting.getId(), tableId);
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SKIPPED");
        verifyNoInteractions(gateway);
    }

    @Test
    void newCallSkipsOldCallButNotNewRequest() {
        var first = call();
        clock.set(START.plusSeconds(10));
        service.callWaiting(owner.getId(), waiting.getId());
        when(gateway.send(any())).thenReturn(new Result(Outcome.DELIVERED, "m2", null));
        dispatcher.poll();
        assertThat(latest(first).status()).isEqualTo("SKIPPED");
        assertThat(deliveries()).filteredOn(d -> d.callCount() == 2).singleElement()
                .extracting(AlimtalkDelivery::status).isEqualTo("SENT");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void permanentFailureDoesNotRetry() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.PERMANENT_FAILURE, null, "INVALID_TEMPLATE"));
        dispatcher.poll();
        clock.set(START.plusSeconds(120));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("FAILED");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void concurrentWorkersClaimAndSendOnlyOnce() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.DELIVERED, "m1", null));
        CompletableFuture.allOf(CompletableFuture.runAsync(dispatcher::poll),
                CompletableFuture.runAsync(dispatcher::poll)).join();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void oldOrDuplicateResultsDoNotOverwriteANewerAttemptOrSuccessfulResult() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.RETRYABLE_FAILURE, "m1", "TEMPORARY"))
                .thenReturn(new Result(Outcome.ACCEPTED, "m2", null));
        dispatcher.poll();
        var retryAt = latest(delivery).nextAttemptAt();
        outbox.recordResult(delivery.id(), 1, new Result(Outcome.RETRYABLE_FAILURE, "m1", "TEMPORARY"),
                START.plusSeconds(10));
        assertThat(latest(delivery).nextAttemptAt()).isEqualTo(retryAt);
        clock.set(START.plusSeconds(30));
        dispatcher.poll();
        outbox.recordResult(delivery.id(), 1, new Result(Outcome.DELIVERED, "m1", null), START.plusSeconds(31));
        assertThat(latest(delivery).status()).isEqualTo("ACCEPTED");
        outbox.recordResult(delivery.id(), 2, new Result(Outcome.DELIVERED, "m2", null), START.plusSeconds(32));
        outbox.recordResult(delivery.id(), 2, new Result(Outcome.PERMANENT_FAILURE, "m2", "STALE"), START.plusSeconds(33));
        assertThat(latest(delivery).status()).isEqualTo("SENT");
    }

    @Test
    void delayedSuccessBeforeRetryCancelsThePendingRetry() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.RETRYABLE_FAILURE, "m1", "TEMPORARY"));
        dispatcher.poll();
        outbox.recordResult(delivery.id(), 1, new Result(Outcome.DELIVERED, "m1", null), START.plusSeconds(10));
        clock.set(START.plusSeconds(30));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void missingAcceptanceIdRemainsUnknownWithoutAnotherSend() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.ACCEPTED, " ", null));
        dispatcher.poll();
        clock.set(START.plusSeconds(120));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("UNKNOWN");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void earlyFinalResultIsNotOverwrittenByTheLaterAcceptanceResponse() {
        var delivery = call();
        when(gateway.send(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, AlimtalkGateway.Request.class);
            outbox.recordResult(request.id(), request.attempt(), new Result(Outcome.DELIVERED, "m1", null), START);
            return new Result(Outcome.ACCEPTED, "m1", null);
        });
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
    }

    @Test
    void knownMessageResultCanBeCheckedAfterCallExpiryWithoutResending() {
        var delivery = call();
        when(gateway.send(any())).thenReturn(new Result(Outcome.ACCEPTED, "m1", null));
        when(gateway.lookup(any(), eq("m1"))).thenReturn(new Result(Outcome.DELIVERED, "m1", null));
        dispatcher.poll();
        clock.set(delivery.expiresAt().plusSeconds(1));
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void slowBatchUsesTheCurrentTimeForEachClaim() {
        call();
        var second = waitings.save(Waiting.create(customer, store, LocalDate.now(clock), 2, 2));
        service.callWaiting(owner.getId(), second.getId());
        when(gateway.send(any())).thenAnswer(invocation -> {
            clock.set(START.plusMinutes(11));
            return new Result(Outcome.DELIVERED, "m1", null);
        });
        dispatcher.poll();
        dispatcher.poll();
        assertThat(deliveries()).extracting(AlimtalkDelivery::status).containsExactlyInAnyOrder("SENT", "SKIPPED");
        verify(gateway, times(1)).send(any());
    }

    @Test
    void authenticatedWebhookRecoversLostResponseAndDuplicatesAreHarmless() throws Exception {
        var delivery = call();
        when(gateway.send(any())).thenThrow(new IllegalStateException("lost response"));
        dispatcher.poll();
        String report = report(delivery.id(), 1, "01000000002", "4000");
        webhook(report, "bad").andExpect(status().isUnauthorized());
        webhook(report, "0".repeat(40)).andExpect(status().isUnauthorized());
        assertThat(latest(delivery).status()).isEqualTo("UNKNOWN");
        webhook(report, secret()).andExpect(status().isOk());
        webhook(report, secret()).andExpect(status().isOk());
        webhook(report(delivery.id(), 1, "01000000002", "3105"), secret()).andExpect(status().isOk());
        assertThat(latest(delivery).status()).isEqualTo("SENT");
        assertThat(latest(delivery).providerMessageId()).isEqualTo("m1");
        clock.set(START.plusSeconds(60));
        dispatcher.poll();
        verify(gateway, times(1)).send(any());
    }

    @Test
    void mismatchedRecipientOrAttemptWebhookDoesNotCompleteDelivery() throws Exception {
        var delivery = call();
        when(gateway.send(any())).thenThrow(new IllegalStateException("lost response"));
        dispatcher.poll();
        webhook(report(delivery.id(), 1, "01099999999", "4000"), secret()).andExpect(status().isOk());
        webhook(report(delivery.id(), 2, "01000000002", "4000"), secret()).andExpect(status().isOk());
        assertThat(latest(delivery).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void simulationRowsCannotBeDispatchedThroughSolapiMode() {
        var delivery = call();
        jdbc.update("UPDATE alimtalk_outbox SET transport_mode = 'simulation' WHERE id = ?", delivery.id());
        dispatcher.poll();
        assertThat(latest(delivery).status()).isEqualTo("PENDING");
        verifyNoInteractions(gateway);
    }

    private org.springframework.test.web.servlet.ResultActions webhook(String body, String secret) throws Exception {
        return mvc.perform(post("/api/integrations/alimtalk/solapi").contentType(MediaType.APPLICATION_JSON)
                .header("X-Solapi-Event-Name", "SINGLE-REPORT").header("X-Solapi-Secret", secret).content(body));
    }

    private String report(UUID id, int attempt, String recipient, String code) throws Exception {
        return mapper.writeValueAsString(List.of(Map.of("messageId", "m1", "type", "ATA", "to", recipient,
                "statusCode", code, "customFields", Map.of("outboxId", id.toString(), "attempt", "" + attempt))));
    }

    private String secret() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest("test-webhook".getBytes(StandardCharsets.UTF_8)));
    }

    private AlimtalkDelivery call() {
        service.callWaiting(owner.getId(), waiting.getId());
        return deliveries().getFirst();
    }

    private void publishCall() {
        var w = waitings.findById(waiting.getId()).orElseThrow();
        events.publishEvent(WaitingCallNotification.of(customer.getId(), w.getId(), w.getWaitNumber(),
                store.getName(), w.getCalledAt(), w.getCallExpiresAt()));
    }

    private AlimtalkDelivery latest(AlimtalkDelivery d) {
        return outbox.find(d.id()).orElseThrow();
    }

    private List<AlimtalkDelivery> deliveries() {
        return jdbc.queryForList("SELECT id FROM alimtalk_outbox ORDER BY created_at, recall_count", UUID.class)
                .stream().map(id -> outbox.find(id).orElseThrow()).toList();
    }
}
