package com.catchtable.notification.durability;

import com.catchtable.notification.repository.SseEmitterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WaitingSseWorkerBurstTest {
    final WaitingSseJournal journal = mock(WaitingSseJournal.class);
    final SseEmitterRepository connections = mock(SseEmitterRepository.class);
    final Clock fixedClock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    final WaitingSseJournal.Root root = new WaitingSseJournal.Root(UUID.randomUUID(), "DIRECT", 1L, 1,
            1L, null, 1L, "WAITING_CANCELED", "{}", false);
    final WaitingSseJournal.Claim claim = new WaitingSseJournal.Claim(root, UUID.randomUUID());

    WaitingSseProperties limits(int roots, long millis) {
        return new WaitingSseProperties(true, true, 1000, 100, 16384, 30, roots, millis);
    }
    WaitingSseWorker worker(WaitingSseProperties limits) {
        return new WaitingSseWorker(journal, limits, connections, new ObjectMapper(), fixedClock);
    }
    void available() {
        when(journal.claim()).thenReturn(claim);
        when(journal.expand(claim)).thenReturn(true);
        when(journal.ready(claim)).thenReturn(List.of());
    }
    SseEmitter sending() {
        available();
        var target = new WaitingSseJournal.Target(UUID.randomUUID(), root.id(), 1L, 1L, 1,
                "generation", "WAITING_CANCELED", "{\"eventId\":\"test\"}");
        var attempt = new WaitingSseJournal.Attempt(UUID.randomUUID(), target, claim.token());
        var emitter = mock(SseEmitter.class);
        when(journal.ready(claim)).thenReturn(List.of(target));
        when(journal.mark(claim, target)).thenReturn(attempt);
        when(journal.authorized(attempt)).thenReturn(true);
        when(connections.find(1L, "generation")).thenReturn(emitter);
        return emitter;
    }

    @Test void scheduledDrainsMultipleRootsButNeverClaimsPastCountBound() {
        available();
        worker(limits(3, 10000)).scheduled();
        verify(journal, times(3)).claim();
        verify(journal, times(3)).finish(claim);
    }
    @Test void idleReturnsAfterOneClaimWithoutSpinning() {
        worker(limits(100, 10000)).scheduled();
        verify(journal).claim();
        verifyNoMoreInteractions(journal);
    }
    @Test void failedDatabaseClaimStopsThePollAndReleasesPermit() {
        when(journal.claim()).thenThrow(new IllegalStateException("DB unavailable")).thenReturn(null);
        var worker = worker(limits(100, 10000));
        worker.scheduled();
        verify(journal).claim();
        worker.scheduled();
        verify(journal, times(2)).claim();
    }
    @Test void deferredExpansionDoesNotAccelerateOtherClaims() {
        when(journal.claim()).thenReturn(claim);
        when(journal.expand(claim)).thenReturn(false);
        worker(limits(100, 10000)).scheduled();
        verify(journal).claim();
        verify(journal, never()).ready(any());
    }
    @Test void failedResultPersistenceStopsAfterThreeWritesWithoutAnotherSendOrClaim() throws Exception {
        var emitter = sending();
        doThrow(new IllegalStateException("write unavailable")).when(journal).saveResult(any());
        worker(limits(100, 10000)).scheduled();
        verify(journal).claim();
        verify(journal, times(3)).saveResult(any());
        verify(journal, never()).finish(any());
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void elapsedBudgetUsesRealMonotonicTimeDespiteFrozenBusinessClock() {
        available();
        doAnswer(invocation -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(60)) LockSupport.parkNanos(1_000_000);
            return null;
        }).when(journal).finish(claim);
        worker(limits(100, 30)).scheduled();
        verify(journal).claim();
        assertThat(fixedClock.instant()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }
    @Test void publicRunOnceStillProcessesOnlyOneRoot() {
        available();
        worker(limits(100, 10000)).runOnce();
        verify(journal).claim();
        verify(journal).finish(claim);
    }
    @Test void blockedSendHoldsSinglePermitAcrossScheduledAndDirectInvocations() throws Exception {
        var emitter = sending();
        when(journal.claim()).thenReturn(claim).thenReturn(null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var worker = worker(limits(100, 10000));
        var thread = new Thread(worker::scheduled);
        thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.runOnce(); worker.scheduled();
            verify(journal).claim();
        } finally { release.countDown(); thread.join(5000); }
        assertThat(thread.isAlive()).isFalse();
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void deliveryDisableBetweenRootsStopsBeforeTheNextClaim() {
        available();
        var enabled = new AtomicBoolean(true);
        var properties = mock(WaitingSseProperties.class);
        when(properties.deliveryEnabled()).thenAnswer(invocation -> enabled.get());
        when(properties.schedulingEnabled()).thenReturn(true);
        when(properties.maxRootsPerPoll()).thenReturn(100);
        when(properties.maxRunMillis()).thenReturn(10000L);
        doAnswer(invocation -> { enabled.set(false); return null; }).when(journal).finish(claim);
        var worker = worker(properties);
        worker.scheduled(); worker.runOnce();
        verify(journal).claim();
    }
    @Test void schedulerDisableBetweenRootsAndBeforeStartDoesNotClaimAgain() {
        available();
        var enabled = new AtomicBoolean(true);
        var properties = mock(WaitingSseProperties.class);
        when(properties.deliveryEnabled()).thenReturn(true);
        when(properties.schedulingEnabled()).thenAnswer(invocation -> enabled.get());
        when(properties.maxRootsPerPoll()).thenReturn(100);
        when(properties.maxRunMillis()).thenReturn(10000L);
        doAnswer(invocation -> { enabled.set(false); return null; }).when(journal).finish(claim);
        var worker = worker(properties);
        worker.scheduled(); worker.scheduled();
        verify(journal).claim();
    }
    @Test void interruptionStopsBetweenRootsAndDoesNotClearInterruptFlag() {
        available();
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; }).when(journal).finish(claim);
        try {
            var worker = worker(limits(100, 10000));
            worker.scheduled(); worker.runOnce();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(journal).claim();
        } finally { Thread.interrupted(); }
    }
    @Test void burstConfigurationHasFinitePositiveDefaultsAndRejectsInvalidLimits() {
        var defaults = new WaitingSseProperties(true, true, 1000, 100, 16384, 30);
        assertThat(defaults.maxRootsPerPoll()).isEqualTo(64);
        assertThat(defaults.maxRunMillis()).isEqualTo(250);
        assertThatThrownBy(() -> limits(0, 250)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(64, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(64, Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }
}
