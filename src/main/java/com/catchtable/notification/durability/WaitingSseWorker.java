package com.catchtable.notification.durability;

import com.catchtable.notification.repository.SseEmitterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingSseWorker {
    private final WaitingSseJournal journal;
    private final WaitingSseProperties properties;
    private final SseEmitterRepository connections;
    private final ObjectMapper mapper;
    private final Clock clock;
    // No unbounded executor queue, and direct/concurrent invocations cannot exceed this process's one send loop.
    private final Semaphore slot = new Semaphore(1);

    @Scheduled(fixedDelayString = "${waiting.sse.poll-delay-ms:100}", scheduler = "waitingSseScheduler")
    public void scheduled() {
        if (properties.schedulingEnabled())
            run(properties.maxRootsPerPoll(), TimeUnit.MILLISECONDS.toNanos(properties.maxRunMillis()), true);
    }

    public void runOnce() {
        run(1, Long.MAX_VALUE, false);
    }

    private void run(int maximumRoots, long budgetNanos, boolean scheduled) {
        if (!properties.deliveryEnabled() || Thread.currentThread().isInterrupted() || !slot.tryAcquire()) return;
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("SSE worker must run outside business transactions");
            long started = System.nanoTime(); // Business Clock may be fixed; runtime must be monotonic.
            for (int roots = 0; roots < maximumRoots && System.nanoTime() - started < budgetNanos; roots++) {
                if (!properties.deliveryEnabled() || (scheduled && !properties.schedulingEnabled())
                        || Thread.currentThread().isInterrupted()) break;
                if (!processOneRoot()) break;
            }
        } catch (RuntimeException failure) {
            log.warn("Waiting SSE worker deferred; durable work retained",failure);
        } finally { slot.release(); }
    }

    /** False means idle/deferred: let fixedDelay elapse before attempting any more work. */
    private boolean processOneRoot() {
        var claim = journal.claim();
        if (claim == null || !journal.expand(claim)) return false;
        for (var target : journal.ready(claim)) {
            var attempt = journal.mark(claim,target); // Returns after commit before any send.
            if (attempt == null || !journal.authorized(attempt)) continue;
            var result = send(attempt);
            boolean persisted = false;
            for (int retry=0; retry<3 && !persisted; retry++) {
                try { journal.saveResult(result); persisted=true; }
                catch (RuntimeException failure) {
                    log.warn("Waiting SSE result persistence unavailable attemptId={} retry={}",result.attemptId(),retry);
                }
            }
            if (!persisted) return false; // Keep remaining READY work; recovery records UNKNOWN.
        }
        journal.finish(claim);
        return true;
    }

    private WaitingSseJournal.Result send(WaitingSseJournal.Attempt attempt) {
        var target = attempt.target();
        var emitter = connections.find(target.memberId(),target.generation());
        String outcome;
        if (emitter == null) outcome="CONNECTION_GONE";
        else {
            try {
                var message = mapper.readTree(target.message());
                emitter.send(SseEmitter.event().id(message.get("eventId").asText()).name(target.type()).data(message));
                outcome="SEND_RETURNED";
            } catch (IOException e) { outcome="IO_ERROR"; connections.delete(target.memberId(),emitter); }
            catch (IllegalStateException e) { outcome="ILLEGAL_STATE"; connections.delete(target.memberId(),emitter); }
            catch (RuntimeException e) { outcome="RUNTIME_ERROR"; connections.delete(target.memberId(),emitter); }
        }
        return new WaitingSseJournal.Result(attempt.id(),outcome,OffsetDateTime.now(clock));
    }
}
