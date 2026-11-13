package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.waiting.dto.WaitingEventReference;
import com.catchtable.waiting.repository.WaitingEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class WaitingSseObservationRecorderTest {
    private final WaitingEventRepository events = mock(WaitingEventRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2031-01-20T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private WaitingSseObservationRecorder recorder;
    private ThreadPoolExecutor writer;

    @BeforeEach
    void prepare() {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> new SimpleTransactionStatus());
        recorder = new WaitingSseObservationRecorder(events, clock, manager);
        writer = (ThreadPoolExecutor) ReflectionTestUtils.getField(recorder, "writer");
    }

    @AfterEach
    void close() { recorder.stop(); }

    @Test
    void boundedBatchesKeepEveryEntryAndCompleteOnlyAfterCommit() throws Exception {
        var enteredCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var commits = new AtomicInteger();
        List<List<WaitingEventRepository.Entry>> batches = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            List<WaitingEventRepository.Entry> entries = invocation.getArgument(0);
            batches.add(List.copyOf(entries));
            return null;
        })
                .when(events).appendBatch(anyList());
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 1) {
                enteredCommit.countDown();
                assertThat(releaseCommit.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(manager).commit(any());
        var releaseWorker = pauseWriter();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 130; i++) futures.add(record("attempt-" + i, i % 2 == 0 ? "EMIT_STARTED" : "EMIT_FINISHED"));
            releaseWorker.countDown();
            assertThat(enteredCommit.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(futures).allMatch(future -> !future.isDone());
        } finally { releaseWorker.countDown(); releaseCommit.countDown(); }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertThat(batches.stream().map(List::size)).containsExactly(64, 64, 2);
        assertThat(commits).hasValue(3);
        var entries = batches.stream().flatMap(List::stream).toList();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String phase = i % 2 == 0 ? "EMIT_STARTED" : "EMIT_FINISHED";
            assertThat(entry.reference()).isEqualTo(new WaitingEventReference(3L, 7, 2, "sse:attempt-" + i + ":" + phase));
            assertThat(entry.occurredAt()).isEqualTo(OffsetDateTime.now(clock));
            assertThat(entry.type()).isEqualTo("SSE_" + phase);
            assertThat(entry.sourceId()).isEqualTo("attempt-" + i);
            assertThat(((Map<?, ?>) entry.details()).get("customerReceipt")).isEqualTo("UNKNOWN");
        }
        verify(events, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedBatchRetriesRowsInSeparateTransactionsAndSettlesEveryFuture() throws Exception {
        doThrow(new IllegalStateException("bad batch row")).when(events).appendBatch(anyList());
        doThrow(new IllegalStateException("bad row")).when(events)
                .append(any(), any(), any(), eq("SSE"), eq("bad"), any());
        var release = pauseWriter();
        var good = record("good", "EMIT_STARTED");
        var bad = record("bad", "EMIT_FINISHED");
        var last = record("last", "EMIT_FINISHED");
        release.countDown();
        good.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> bad.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        last.get(5, TimeUnit.SECONDS);
        verify(manager, times(4)).getTransaction(any()); // failed batch + each row
        verify(manager, times(2)).rollback(any());
        verify(manager, times(2)).commit(any());
    }

    @Test
    void commitFailureDoesNotPrematurelyCompleteAndReusesTheSameEventKeys() throws Exception {
        doThrow(new IllegalStateException("commit outcome unknown")).doNothing().when(manager).commit(any());
        var release = pauseWriter();
        var first = record("same", "EMIT_STARTED");
        var second = record("same", "EMIT_FINISHED");
        release.countDown();
        first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        verify(events).append(eq(new WaitingEventReference(3L, 7, 2, "sse:same:EMIT_STARTED")),
                eq("SSE_EMIT_STARTED"), eq(OffsetDateTime.now(clock)), eq("SSE"), eq("same"), any());
        verify(events).append(eq(new WaitingEventReference(3L, 7, 2, "sse:same:EMIT_FINISHED")),
                eq("SSE_EMIT_FINISHED"), eq(OffsetDateTime.now(clock)), eq("SSE"), eq("same"), any());
    }

    @Test
    void fullQueueRejectsExplicitlyWithoutExecutingPersistenceOnTheCaller() throws Exception {
        var release = pauseWriter();
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        try {
            for (int i = 0; i < 512; i++) accepted.add(record("queued-" + i, "EMIT_FINISHED"));
            var rejected = record("overflow", "EMIT_FINISHED");
            assertThat(rejected.isCompletedExceptionally()).isTrue();
            assertThat(accepted).allMatch(future -> !future.isDone());
            verifyNoInteractions(events);
        } finally { release.countDown(); }
        CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void gracefulStopDrainsAcceptedObservationsAndRejectsNewWork() {
        var future = record("before-stop", "EMIT_FINISHED");
        recorder.stop();
        assertThat(future).isCompleted();
        assertThat(future.isCompletedExceptionally()).isFalse();
        assertThat(record("after-stop", "EMIT_FINISHED").isCompletedExceptionally()).isTrue();
    }

    @Test
    void interruptedStopSettlesQueuedAndInFlightFuturesWithoutClaimingRollback() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            // Simulate a driver that cannot cancel an in-flight commit immediately.
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return null;
        }).when(events).appendBatch(anyList());
        var active = record("active", "EMIT_STARTED");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var queued = record("queued", "EMIT_FINISHED");
        try {
            Thread.currentThread().interrupt();
            recorder.stop();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(active.isCompletedExceptionally()).isTrue();
            assertThat(queued.isCompletedExceptionally()).isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
            assertThat(writer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        // A late successful commit must not turn the already reported UNKNOWN future into success.
        assertThat(active.isCompletedExceptionally()).isTrue();
    }

    private CompletableFuture<Void> record(String attempt, String phase) {
        var event = new CustomerNotificationEvent(1L, NotificationType.WAITING_CALLED, 3L, "test")
                .withWaitingEvent(new WaitingEventReference(3L, 7, 2, "business-call-7"));
        return recorder.record(event, attempt, phase, 0, 0, 0, 0);
    }

    private CountDownLatch pauseWriter() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        writer.execute(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer release timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        return release;
    }
}
