package com.catchtable.payment;

import com.catchtable.CatchtableApplication;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local probe only, added to test classpath by probe.gradle; identical for before/after. */
public class PaymentIsolationProbe {
    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(CatchtableApplication.class, Fixture.class, IntegrationSseProbe.class).run(args);
        context.getBeansOfType(ThreadPoolTaskScheduler.class).forEach((name, scheduler) ->
                System.out.printf("PROBE_SCHEDULER bean=%s pool=%d prefix=%s%n",
                        name, scheduler.getPoolSize(), scheduler.getThreadNamePrefix()));
    }
    @TestConfiguration
    public static class Fixture {
        @Bean @Primary public Clock probeClock() {
            return Clock.offset(Clock.system(ZoneId.of("Asia/Seoul")),
                    Duration.between(Instant.now(), Instant.parse("2030-01-20T03:00:00Z")));
        }
        @Bean public Object scheduleLagProbe(@org.springframework.beans.factory.annotation.Qualifier("taskScheduler") TaskScheduler taskScheduler, MeterRegistry registry) {
            AtomicLong last = new AtomicLong(System.nanoTime());
            registry.gauge("probe.scheduler.lag.ms", last,
                    t -> Math.max(0,(System.nanoTime()-t.get())/1_000_000.0-100));
            java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
            taskScheduler.scheduleAtFixedRate(() -> {
                if (first.getAndSet(false)) System.out.println("PROBE_COMMON_THREAD " + Thread.currentThread().getName());
                last.set(System.nanoTime());
            }, Duration.ofMillis(100));
            return last;
        }
    }
}
