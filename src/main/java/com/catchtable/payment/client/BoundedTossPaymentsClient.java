package com.catchtable.payment.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** All operations share admission and a bounded HTTP executor. Only HTTP runs on transport threads. */
public class BoundedTossPaymentsClient implements TossPaymentsClient, AutoCloseable {
    private final TossPaymentsClient delegate;
    private final PaymentCallGate gate;
    private final MeterRegistry metrics;
    private final Duration callTimeout;
    private final ThreadPoolExecutor transport;
    private final AtomicInteger active = new AtomicInteger();

    public BoundedTossPaymentsClient(TossPaymentsClient delegate, PaymentCallGate gate, MeterRegistry metrics,
                                    Duration callTimeout) {
        if (callTimeout.toMillis() < 1) throw new IllegalArgumentException("toss.call-timeout must be at least 1ms");
        this.delegate = delegate;
        this.gate = gate;
        this.metrics = metrics;
        this.callTimeout = callTimeout;
        AtomicInteger sequence = new AtomicInteger();
        this.transport = new ThreadPoolExecutor(gate.maximum(), gate.maximum(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(gate.maximum()), action -> {
                    Thread thread = new Thread(action, "payment-http-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        metrics.gauge("payment.pg.active", active);
    }

    private TossPaymentResponse call(String operation, Supplier<TossPaymentResponse> request) {
        return gate.call(() -> {
            Runnable release = gate.retainTransport();
            CompletableFuture<TossPaymentResponse> result = new CompletableFuture<>();
            try {
                transport.execute(() -> {
                    active.incrementAndGet();
                    Timer.Sample sample = Timer.start(metrics);
                    try { result.complete(request.get()); }
                    catch (Throwable failure) { result.completeExceptionally(failure); }
                    finally {
                        sample.stop(metrics.timer("payment.pg.duration", "operation", operation));
                        active.decrementAndGet();
                        release.run();
                    }
                });
            } catch (RejectedExecutionException stopped) {
                release.run();
                // Shutdown after business preparation has the same recovery boundary as a process interruption.
                throw new TossPaymentsUnknownResultException("PG 전송기가 종료되어 결과 재확인이 필요합니다.", stopped);
            }
            try {
                return result.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                metrics.counter("payment.pg.deadline").increment();
                // Do not free the slot or infer failure: a provider can still commit after this deadline.
                throw new TossPaymentsUnknownResultException("PG 전체 호출 시간 제한을 초과했습니다.", timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TossPaymentsUnknownResultException("PG 응답 대기가 중단됐습니다.", interrupted);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failed.getCause() instanceof Error error) throw error;
                throw new TossPaymentsUnknownResultException("PG 응답을 받지 못했습니다.", failed.getCause());
            }
        });
    }

    public TossPaymentResponse confirm(String key, String order, long amount, String idempotencyKey) {
        return call("confirm", () -> delegate.confirm(key, order, amount, idempotencyKey));
    }
    public TossPaymentResponse cancel(String key, Long amount, String reason, String idempotencyKey) {
        return call("cancel", () -> delegate.cancel(key, amount, reason, idempotencyKey));
    }
    public TossPaymentResponse getByPaymentKey(String key) {
        return call("query", () -> delegate.getByPaymentKey(key));
    }
    public TossPaymentResponse getByOrderId(String order) {
        return call("query", () -> delegate.getByOrderId(order));
    }
    @Override public void close() { transport.shutdown(); }
}
