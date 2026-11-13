package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.waiting.dto.WaitingEventReference;
import com.catchtable.waiting.repository.WaitingEventRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WaitingSseObservationRecorder {
    private static final int MAX_BATCH_SIZE = 64;
    private final WaitingEventRepository events;
    private final Clock clock;

    private final TransactionTemplate transactions;
    private final Set<ObservationTask> unsettled = ConcurrentHashMap.newKeySet();
    private volatile boolean aborting;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), task -> {
                Thread thread = new Thread(task, "waiting-sse-observation");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public WaitingSseObservationRecorder(WaitingEventRepository events, Clock clock, PlatformTransactionManager manager) {
        this.events = events;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    // Historical V36 regression fixture only. V38 production delivery uses WaitingSseJournal.
    public CompletableFuture<Void> record(CustomerNotificationEvent event, String attemptId, String phase,
                       int connections, int attempted, int succeeded, int failed) {
        var parent = event.waitingEvent();
        Long waitingId = parent == null ? event.targetId() : parent.waitingId();
        var ref = new WaitingEventReference(waitingId, parent == null ? null : parent.callCount(),
                parent == null ? null : parent.recallCount(), "sse:" + attemptId + ":" + phase);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("businessEventKey", parent == null ? null : parent.eventKey());
        details.put("notificationType", event.type());
        details.put("recipientRole", event.type() == NotificationType.WAITING_CHANGED ? "OWNER" : "CUSTOMER");
        details.put("connections", connections);
        details.put("attempted", attempted);
        details.put("serverSendSucceeded", succeeded);
        details.put("serverSendFailed", failed);
        details.put("serverSendUnknown", attempted - succeeded - failed);
        details.put("unattempted", connections - attempted);
        details.put("customerReceipt", "UNKNOWN");
        OffsetDateTime observedAt = OffsetDateTime.now(clock);
        var task = new ObservationTask(new WaitingEventRepository.Entry(
                ref, "SSE_" + phase, observedAt, "SSE", attemptId, details));
        unsettled.add(task);
        task.completion.whenComplete((unused, failure) -> unsettled.remove(task));
        try {
            writer.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("웨이팅 SSE 관찰 큐 포화/종료 waitingId={}, attemptId={}, phase={}", waitingId, attemptId, phase);
            task.completion.completeExceptionally(e);
        }
        return task.completion;
    }

    private final class ObservationTask implements Runnable {
        private final WaitingEventRepository.Entry entry;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private ObservationTask(WaitingEventRepository.Entry entry) { this.entry = entry; }

        @Override
        public void run() {
            List<ObservationTask> batch = new ArrayList<>(MAX_BATCH_SIZE);
            batch.add(this);
            // One consumer, no waiting/coalescing: preserve FIFO and never absorb a non-observation barrier.
            while (batch.size() < MAX_BATCH_SIZE && writer.getQueue().peek() instanceof ObservationTask) {
                Runnable next = writer.getQueue().poll();
                if (next == null) break; // shutdownNow may have drained the queue
                batch.add((ObservationTask) next);
            }
            persist(batch);
        }
    }

    private void persist(List<ObservationTask> batch) {
        if (aborting) return; // stopNow completes every unsettled future with an explicit unknown result
        try {
            transactions.executeWithoutResult(tx -> events.appendBatch(batch.stream().map(t -> t.entry).toList()));
            batch.forEach(task -> task.completion.complete(null)); // only after commit
        } catch (RuntimeException failure) {
            log.warn("웨이팅 SSE 관찰 묶음 저장 실패; 개별 트랜잭션으로 재시도 count={}", batch.size(), failure);
            // A single bad row must not lose its healthy neighbours. Stable event keys also make an
            // ambiguous batch commit safe to retry. Never retry in the failed/rollback-only transaction.
            for (ObservationTask task : batch) {
                if (aborting) return;
                var entry = task.entry;
                try {
                    transactions.executeWithoutResult(tx -> events.append(entry.reference(), entry.type(),
                            entry.occurredAt(), entry.sourceType(), entry.sourceId(), entry.details()));
                    task.completion.complete(null);
                } catch (RuntimeException e) {
                    log.warn("웨이팅 SSE 관찰 저장 실패 waitingId={}, attemptId={}, phase={}",
                            entry.reference().waitingId(), entry.sourceId(), entry.type(), e);
                    task.completion.completeExceptionally(e);
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                stopNow();
            }
        } catch (InterruptedException e) {
            stopNow();
            Thread.currentThread().interrupt();
        }
    }

    private void stopNow() {
        aborting = true;
        writer.shutdownNow();
        var failure = new IllegalStateException("Observation writer stopped before persistence was confirmed");
        // Includes tasks already removed from the queue into the active batch. An in-flight JDBC commit
        // may still finish after shutdown; exceptional completion here means UNKNOWN, not definite loss.
        for (ObservationTask task : unsettled) {
            if (task.completion.completeExceptionally(failure)) {
                log.warn("웨이팅 SSE 관찰 종료 시 저장 결과 미확인 eventKey={}", task.entry.reference().eventKey());
            }
        }
    }
}
