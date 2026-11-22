package com.catchtable.waiting.service;

import com.catchtable.waiting.dto.WaitingBatchItem;
import com.catchtable.waiting.dto.WaitingSubmission;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WaitingIntakeBatcher {
    private record Call(WaitingBatchItem item, long arrived, CompletableFuture<WaitingBatchWriter.Decision> reply) {}
    private static final class Lane {
        final ArrayDeque<Call> pending = new ArrayDeque<>();
        ScheduledFuture<?> scheduled;
        long generation;
        boolean writing;
    }

    private final Map<Long,Lane> lanes = new HashMap<>();
    private final ScheduledThreadPoolExecutor writers = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().daemon(true).name("waiting-intake-writer-",0).factory());
    private final WaitingBatchWriter writer;
    private final DistributionSummary committedSizes;
    private final AtomicInteger pending;
    private final boolean enabled;
    private final int size;
    private final long windowNanos;
    private boolean closed;

    public WaitingIntakeBatcher(WaitingBatchWriter writer, MeterRegistry meters,
                                @Value("${waiting.intake.batch.enabled:false}") boolean enabled,
                                @Value("${waiting.intake.batch.size:256}") int size,
                                @Value("${waiting.intake.batch.window-ms:48}") long windowMs) {
        if(size<1 || size>1024 || windowMs<0) throw new IllegalArgumentException("Invalid intake batch settings");
        this.writer=writer;
        this.enabled=enabled;
        this.size=size;
        this.windowNanos=TimeUnit.MILLISECONDS.toNanos(windowMs);
        this.committedSizes=meters.summary("waiting.intake.batch.requests");
        this.pending=meters.gauge("waiting.intake.batch.pending",new AtomicInteger());
        writers.setRemoveOnCancelPolicy(true);
    }

    boolean enabled() { return enabled; }

    private CompletableFuture<WaitingBatchWriter.Decision> enqueue(long store, WaitingBatchItem item) {
        var call = new Call(item,System.nanoTime(),new CompletableFuture<>());
        synchronized (lanes) {
            if(closed) throw new RejectedExecutionException("Waiting admission is stopping");
            var lane=lanes.computeIfAbsent(store,unused -> new Lane());
            lane.pending.addLast(call);
            pending.incrementAndGet();
            if(!lane.writing && (lane.scheduled==null || lane.pending.size()==size)) {
                if(lane.scheduled!=null) lane.scheduled.cancel(false);
                schedule(store,lane);
            }
        }
        return call.reply;
    }

    Optional<WaitingSubmission> submit(long store, WaitingBatchItem item) {
        try {
            return enqueue(store,item).join().resolve();
        } catch (CompletionException e) {
            if(e.getCause() instanceof RuntimeException cause) throw cause;
            if(e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
    }

    // Called with lanes locked. Requeue after one transaction so independent stores can run.
    private void schedule(long store,Lane lane) {
        long delay = remainingWindow(lane);
        long generation=++lane.generation;
        lane.scheduled=writers.schedule(()->write(store,lane,generation),delay,TimeUnit.NANOSECONDS);
    }

    private long remainingWindow(Lane lane) {
        if(lane.pending.size()>=size) return 0;
        long collect=lane.pending.size()<4 ? Math.min(windowNanos,TimeUnit.MILLISECONDS.toNanos(2)) : windowNanos;
        return Math.max(0,collect-(System.nanoTime()-lane.pending.getFirst().arrived));
    }

    private void write(long store,Lane lane,long generation) {
        List<Call> calls;
        synchronized (lanes) {
            if(closed || lane.generation!=generation) return;
            if (remainingWindow(lane)>0) {
                schedule(store,lane);
                return;
            }
            lane.writing=true;
            lane.scheduled=null;
            calls=new ArrayList<>(Math.min(size,lane.pending.size()));
            while(calls.size()<size && !lane.pending.isEmpty()) calls.add(lane.pending.removeFirst());
        }
        List<WaitingBatchWriter.Decision> decisions=null;
        Throwable error=null;
        try {
            var items=calls.stream().map(Call::item).toList();
            var batch=writer.regular(store,items).or(()->writer.write(store,items));
            decisions=batch.orElseGet(()->Collections.nCopies(calls.size(),WaitingBatchWriter.Decision.DIRECT));
            if(batch.isPresent()) committedSizes.record(calls.size());
        } catch(RuntimeException | Error failure) {
            error=failure;
        }
        // PostgreSQL has now committed or rolled back the entire prefix. Wake the next
        // writer before delivering these responses; a slow reader cannot hold the lane.
        synchronized(lanes) {
            lane.writing=false;
            if(lane.pending.isEmpty()) lanes.remove(store,lane);
            else if(!closed) schedule(store,lane);
        }
        for(int i=0;i<calls.size();i++) {
            if(error==null) calls.get(i).reply.complete(decisions.get(i));
            else calls.get(i).reply.completeExceptionally(error);
        }
        pending.addAndGet(-calls.size());
    }

    @PreDestroy void close() {
        List<Call> abandoned=new ArrayList<>();
        synchronized (lanes) {
            closed=true;
            for(var lane:lanes.values()) {
                if(lane.scheduled!=null) lane.scheduled.cancel(false);
                abandoned.addAll(lane.pending);
                lane.pending.clear();
            }
            lanes.clear();
        }
        var stopping=new RejectedExecutionException("Waiting admission stopped before commit");
        abandoned.forEach(c->c.reply.completeExceptionally(stopping));
        pending.addAndGet(-abandoned.size());
        writers.shutdown();
    }
}
