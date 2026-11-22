package com.catchtable.waiting.service;

import com.catchtable.waiting.dto.WaitingBatchItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WaitingIntakeBatcherTest {
    @Test
    void queuedApplicantsStayTogetherWhileThePreviousTransactionIsBusy() throws Exception {
        var writer = mock(WaitingBatchWriter.class);
        var sizes = new CopyOnWriteArrayList<Integer>();
        var entered = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        when(writer.write(eq(1L), anyList())).thenAnswer(call -> {
            List<WaitingBatchItem> input = call.getArgument(1);
            sizes.add(input.size());
            if (sizes.size() == 1) {
                entered.countDown();
                assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return Optional.of(Collections.nCopies(input.size(), WaitingBatchWriter.Decision.DIRECT));
        });
        var meters = new SimpleMeterRegistry();
        var batcher = new WaitingIntakeBatcher(writer, meters, true, 8, 2);
        try (var threads = Executors.newFixedThreadPool(6)) {
            var calls = new ArrayList<Future<?>>();
            try {
                calls.add(threads.submit(() -> batcher.submit(1, item(1))));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (long m=2; m<=6; m++) {
                    var request = item(m);
                    calls.add(threads.submit(() -> batcher.submit(1, request)));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (meters.get("waiting.intake.batch.pending").gauge().value() < m
                            && System.nanoTime() < deadline) Thread.sleep(1);
                    assertThat(meters.get("waiting.intake.batch.pending").gauge().value()).isEqualTo((double)m);
                    Thread.sleep(8); // Deliberately longer than the 2ms collection window.
                }
                assertThat(calls).allMatch(f -> !f.isDone());
            } finally {
                commit.countDown();
            }
            for (var call : calls) call.get(3, TimeUnit.SECONDS);
        }
        assertThat(sizes).containsExactly(1,5);
        assertThat(meters.get("waiting.intake.batch.pending").gauge().value()).isZero();
    }

    private WaitingBatchItem item(long member) {
        return new WaitingBatchItem(new WaitingBatchItem.Key(member, "test"), 2);
    }
}
