package com.catchtable.waiting.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncReceiptAdmissionTest {
    @Test
    void aLargeConfiguredLimitStillBoundsOutstandingHttpContexts() throws Exception {
        var admission = new AsyncReceiptAdmission(true, 4_000, 2);
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Supplier<String> delayedCommit = () -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test commit did not resume");
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            return "committed";
        };
        try {
            assertThat(admission.handle("one", delayedCommit)).isInstanceOf(DeferredResult.class);
            assertThat(admission.handle("two", delayedCommit)).isInstanceOf(DeferredResult.class);
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            Thread caller = Thread.currentThread();
            assertThat(admission.handle("three", Thread::currentThread)).isSameAs(caller);
        } finally {
            release.countDown();
            admission.close();
        }
    }
}
