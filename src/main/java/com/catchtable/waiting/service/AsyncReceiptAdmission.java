package com.catchtable.waiting.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Release the servlet thread while an applicant awaits its store's commit. Direct calls remain bounded by Tomcat. */
@Component
public class AsyncReceiptAdmission {
    private final boolean enabled;
    private final Semaphore slots;
    private final ExecutorService tasks=Executors.newVirtualThreadPerTaskExecutor();
    public AsyncReceiptAdmission(@Value("${waiting.intake.async.enabled:false}") boolean enabled,
            @Value("${waiting.intake.async.max-in-flight:${server.tomcat.threads.max:200}}") int capacity,
            @Value("${server.tomcat.threads.max:200}") int servletThreads) {
        if (capacity < 1 || servletThreads < 1) throw new IllegalArgumentException("Invalid async intake capacity");
        this.enabled = enabled;
        // A deferred response retains a full servlet request until its commit completes.
        // Excess requests follow Tomcat's existing bounded synchronous path.
        this.slots = new Semaphore(Math.min(capacity, servletThreads));
    }

    public <T> Object handle(String key,Supplier<T> action) {
        if(!enabled || key==null || TransactionSynchronizationManager.isActualTransactionActive() || !slots.tryAcquire()) {
            return action.get();
        }
        var deferred=new DeferredResult<T>();
        try {
            tasks.execute(()->{
                try { deferred.setResult(action.get()); }
                catch(RuntimeException | Error failure) { deferred.setErrorResult(failure); }
                finally { slots.release(); }
            });
        } catch(java.util.concurrent.RejectedExecutionException shutdown) {
            slots.release();
            throw shutdown;
        }
        return deferred;
    }

    @PreDestroy void close() { tasks.shutdown(); }
}
