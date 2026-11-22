package com.catchtable.waiting.service;

import com.catchtable.waiting.dto.WaitingBatchItem;
import com.catchtable.waiting.dto.WaitingSubmission;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WaitingIntakeBatcher {
    private record Call(WaitingBatchItem item, CompletableFuture<WaitingBatchWriter.Decision> reply) {}

    private static final class Group {
        final long opened = System.nanoTime();
        final List<Call> calls = new ArrayList<>();
        final CountDownLatch full = new CountDownLatch(1);
        final CompletableFuture<Void> predecessor;
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        boolean sealed;

        Group(Group previous) {
            predecessor = previous == null ? CompletableFuture.completedFuture(null) : previous.finished;
        }
    }

    private final Map<Long, Group> tails = new HashMap<>();
    private final WaitingBatchWriter writer;
    private final DistributionSummary committedSizes;
    private final AtomicInteger pending;
    private final boolean enabled;
    private final int size;
    private final long windowNanos;

    public WaitingIntakeBatcher(WaitingBatchWriter writer, MeterRegistry metrics,
                                @Value("${waiting.intake.batch.enabled:false}") boolean enabled,
                                @Value("${waiting.intake.batch.size:32}") int size,
                                @Value("${waiting.intake.batch.window-ms:2}") long windowMs) {
        if (size < 1 || windowMs < 0) {
            throw new IllegalArgumentException("Invalid intake batch size or window");
        }
        this.writer = writer;
        this.committedSizes = metrics.summary("waiting.intake.batch.requests");
        this.pending = metrics.gauge("waiting.intake.batch.pending", new AtomicInteger());
        this.enabled = enabled;
        this.size = size;
        this.windowNanos = TimeUnit.MILLISECONDS.toNanos(windowMs);
    }

    boolean enabled() {
        return enabled;
    }

    // The first request leads its group. No new executor or DB connection is needed while forming a batch.
    Optional<WaitingSubmission> submit(long storeId, WaitingBatchItem item) {
        var call = new Call(item, new CompletableFuture<>());
        Group group;
        boolean leader;
        synchronized (tails) {
            group = tails.get(storeId);
            leader = group == null || group.sealed;
            if (leader) {
                group = new Group(group);
                tails.put(storeId, group);
            }
            group.calls.add(call);
            pending.incrementAndGet();
            if (group.calls.size() >= size) {
                group.sealed = true;
                group.full.countDown();
            }
        }
        try {
            if (leader) flush(storeId, group);
            return call.reply().join().resolve();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        } finally {
            pending.decrementAndGet();
        }
    }

    private void flush(long storeId, Group group) {
        // Keep accepting up to size while the previous transaction owns this store. When it
        // finishes, only the unused part of the collection window may add further delay.
        group.predecessor.join();
        boolean interrupted = false;
        try {
            long remaining = windowNanos - (System.nanoTime() - group.opened);
            if (remaining > 0) {
                group.full.await(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        }

        List<Call> calls;
        synchronized (tails) {
            group.sealed = true;
            calls = List.copyOf(group.calls);
        }
        try {
            // The Spring proxy commits before write returns. No caller sees a receipt until this point.
            var batch = writer.write(storeId, calls.stream().map(Call::item).toList());
            var decisions = batch.orElseGet(() -> Collections.nCopies(calls.size(), WaitingBatchWriter.Decision.DIRECT));
            if (batch.isPresent()) committedSizes.record(calls.size());
            for (int i = 0; i < calls.size(); i++) {
                calls.get(i).reply().complete(decisions.get(i));
            }
        } catch (RuntimeException | Error failure) {
            calls.forEach(call -> call.reply().completeExceptionally(failure));
        } finally {
            group.finished.complete(null);
            synchronized (tails) {
                tails.remove(storeId, group);
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
