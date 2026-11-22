package com.catchtable.waiting.service;

import com.catchtable.global.redis.RedisEndpoint;
import com.catchtable.waiting.dto.WaitingLane;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import com.catchtable.waiting.dto.WaitingSubmission;
import com.catchtable.waiting.repository.ReceiptWorkRepository;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Redis schedules lanes. Only the PostgreSQL ledger can select a receipt or authorize registration. */
@Service
public class WaitingDispatch {
    private static final Logger log=LoggerFactory.getLogger(WaitingDispatch.class);
    private static final String READY="ct:waiting:{dispatch}:ready", ORDER="ct:waiting:{dispatch}:order";
    private static final String OFFER="""
            if not redis.call('ZSCORE',KEYS[1],ARGV[1]) then
              redis.call('ZADD',KEYS[1],redis.call('INCR',KEYS[2]),ARGV[1])
            end
            return 1
            """;
    private final RedisEndpoint redis;
    private final ReceiptWorkRepository ledger;
    private final ReceiptRegistration registration;
    private final MeterRegistry meters;
    private final boolean enabled,immediate;
    private final int quantum;
    private final long scanNanos;
    private UUID reconciled;
    private long nextScan;

    public WaitingDispatch(@Qualifier("waitingRedis") RedisEndpoint redis, ReceiptWorkRepository ledger,
            ReceiptRegistration registration, MeterRegistry meters,
            @Value("${waiting.dispatch.enabled:false}") boolean enabled,
            @Value("${waiting.dispatch.immediate:true}") boolean immediate,
            @Value("${waiting.dispatch.quantum:16}") int quantum,
            @Value("${waiting.dispatch.reconcile-ms:300}") long scanMs) {
        if(quantum<1||scanMs<1) throw new IllegalArgumentException("Invalid receipt dispatch settings");
        this.redis=redis;this.ledger=ledger;this.registration=registration;this.meters=meters;
        this.enabled=enabled;this.immediate=immediate;this.quantum=quantum;scanNanos=TimeUnit.MILLISECONDS.toNanos(scanMs);
    }

    private static void offer(RedisEndpoint.Session s,WaitingLane lane) {
        s.commands().eval(OFFER, ScriptOutputType.INTEGER,new String[]{READY,ORDER},lane.key());
    }

    private synchronized void reconcile(RedisEndpoint.Session session) {
        long now=System.nanoTime();
        if(session.generation().equals(reconciled) && now<nextScan) return;
        // Re-publish every unfinished lane on a fresh master/connection before taking hints from it.
        for(var lane:ledger.lanes()) offer(session,lane);
        reconciled=session.generation();
        nextScan=now+scanNanos;
    }

    public WaitingReceiptResponse afterCommit(long member,WaitingSubmission.Receipt accepted) {
        var r=accepted.response();
        if (!accepted.created()) return registration.view(member,r.receiptId());
        if (!enabled || !immediate || !accepted.idleHead() || TransactionSynchronizationManager.isActualTransactionActive()) return r;
        try {
            var lane=new WaitingLane(r.storeId(),r.receivedDate());
            redis.use(s->{reconcile(s);offer(s,lane);return true;});
            var head=registration.claim(lane,r.receiptId());
            if(head.isPresent()) registration.complete(head.get());
            return registration.view(member,r.receiptId());
        } catch(RuntimeException failure) {
            // The caller already owns a committed ticket; independent reconciliation will retry delivery.
            meters.counter("waiting.dispatch.immediate.deferred").increment();
            return r;
        }
    }

    // Cancellation remains available through PostgreSQL while the Redis queue is being recovered.
    @Scheduled(scheduler="waitingReceiptScheduler",fixedDelayString="${waiting.dispatch.cancel-scan-ms:250}")
    public void reconcileCancellations() {
        if(!enabled) return;
        for(var lane:ledger.cancellationLanes()) {
            for(int i=0;i<quantum && registration.settleOneCancel(lane);i++) { /* drain a bounded slice */ }
        }
    }

    @Scheduled(scheduler="waitingReceiptScheduler",fixedDelayString="${waiting.dispatch.poll-ms:25}")
    public void tick() {
        if(!enabled) return;
        try {
            var hint=redis.use(s->{reconcile(s);return s.commands().zpopmin(READY);});
            if(hint==null || !hint.hasValue()) return;
            WaitingLane lane=WaitingLane.parse(hint.getValue());
            var head = registration.claimPrefix(lane,null,quantum);
            if (head.isPresent()) {
                try {
                    registration.complete(head.get());
                } catch (RuntimeException batchFailure) {
                    // A later member (for example, the SSE capacity boundary) must not prevent
                    // an earlier valid head from completing. The batch transaction has rolled back.
                    if (head.get().rows().size()==1) throw batchFailure;
                    registration.completeFirstAndReleaseRemainder(head.get());
                    meters.counter("waiting.dispatch.batch.fallback").increment();
                }
            }
            if(ledger.hasWork(lane)) redis.use(s->{reconcile(s);offer(s,lane);return true;});
        } catch(RedisException unavailable) {
            meters.counter("waiting.dispatch.redis.unavailable").increment();
        } catch(RuntimeException failure) {
            meters.counter("waiting.dispatch.retry").increment();
            log.warn("Waiting dispatch failed; ledger retains unfinished work",failure);
        }
    }
}
