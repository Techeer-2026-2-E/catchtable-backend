package com.catchtable.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ReconcileTestClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public ReconcileTestClock() {
        this(new AtomicReference<>(Instant.parse("2030-01-20T03:00:00Z")), ZoneId.of("Asia/Seoul"));
    }

    private ReconcileTestClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = Objects.requireNonNull(zone);
    }

    public void set(OffsetDateTime value) {
        now.set(value.toInstant());
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId value) { return new ReconcileTestClock(now, value); }
    @Override public Instant instant() { return now.get(); }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        ReconcileTestClock reconcileTestClock() { return new ReconcileTestClock(); }
    }
}
