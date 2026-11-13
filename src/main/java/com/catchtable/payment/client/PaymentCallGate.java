package com.catchtable.payment.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Per-process, zero-wait admission. A timed-out HTTP task retains its permit until it actually exits. */
@Component
public class PaymentCallGate {
    private final Semaphore permits;
    private final int maximum;
    private final ThreadLocal<Lease> admitted = new ThreadLocal<>();
    private final MeterRegistry metrics;

    public PaymentCallGate(@Value("${toss.max-concurrent-calls:8}") int maximum, MeterRegistry metrics) {
        if (maximum < 1) throw new IllegalArgumentException("toss.max-concurrent-calls must be positive");
        this.maximum = maximum;
        this.permits = new Semaphore(maximum);
        this.metrics = metrics;
        metrics.gauge("payment.pg.admitted", permits, p -> maximum - p.availablePermits());
    }

    public int maximum() { return maximum; }

    public <T> T call(Supplier<T> action) {
        if (admitted.get() != null) return action.get();
        if (!permits.tryAcquire()) {
            metrics.counter("payment.pg.rejected").increment();
            throw new PaymentCapacityExceededException();
        }
        Lease lease = new Lease();
        admitted.set(lease);
        try { return action.get(); }
        finally {
            admitted.remove();
            lease.release();
        }
    }

    /** Retain the existing admission for the bounded transport executor; release exactly once in its finally. */
    Runnable retainTransport() {
        Lease lease = admitted.get();
        if (lease == null) throw new IllegalStateException("PG transport must be admitted first");
        lease.references.incrementAndGet();
        return lease::release;
    }

    private class Lease {
        private final AtomicInteger references = new AtomicInteger(1);
        void release() {
            if (references.decrementAndGet() == 0) permits.release();
        }
    }
}
