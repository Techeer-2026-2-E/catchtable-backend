package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.repository.SseEmitterRepository;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SseServletOutputTest {
    @Test
    void firstWritableCallbackRegistersAndFlushesTheGreetingExactlyOnce() throws IOException {
        var wire = new ReadyStream();
        var output = new SseServletOutput(wire, Duration.ofSeconds(1));
        output.write("event:connect\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
        var registered = new AtomicBoolean();
        output.arm(() -> assertThat(registered.compareAndSet(false, true)).isTrue(), () -> {});
        wire.listener.onWritePossible();
        assertThat(registered).isFalse();
        assertThat(wire.bytes.size()).isZero();

        wire.ready = true;
        wire.listener.onWritePossible();
        wire.listener.onWritePossible();
        output.write("next".getBytes(StandardCharsets.UTF_8));
        output.flush();
        assertThat(registered).isTrue();
        assertThat(wire.bytes.toString(StandardCharsets.UTF_8)).isEqualTo("event:connect\n\nnext");
    }

    @Test
    void stalledServletOutputsReleaseAllPhysicalSlotsWithoutBecomingWritable() throws Exception {
        var repo = new SseEmitterRepository();
        var writer = new SseConnectionWriter(repo, Duration.ofMillis(300), 2);
        var aborted = new CountDownLatch(2);
        try {
            var first = stalled(repo, writer, 1L, aborted);
            var second = stalled(repo, writer, 2L, aborted);
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(SseConnectionWriter.Outcome.RETRY);
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(SseConnectionWriter.Outcome.RETRY);
            assertThat(aborted.await(1, TimeUnit.SECONDS)).isTrue();
            var healthy = mock(SseEmitter.class);
            repo.save(3L, healthy);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(writer.send(NotificationJobKind.NOTIFY, 3L, healthy, SseEmitter.event()))
                            .isEqualTo(SseConnectionWriter.Outcome.SENT));
            verify(healthy).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            writer.close();
        }
    }

    private CompletableFuture<SseConnectionWriter.Outcome> stalled(SseEmitterRepository repo, SseConnectionWriter writer,
                                                                    long id, CountDownLatch aborted) throws Exception {
        var wire = new ReadyStream();
        var output = new SseServletOutput(wire, Duration.ofSeconds(5));
        wire.ready = true;
        output.arm(() -> {}, aborted::countDown);
        wire.listener.onWritePossible();
        wire.ready = false;
        var emitter = mock(SseEmitter.class);
        repo.save(id, emitter);
        doAnswer(invocation -> { output.write(1); return null; }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var result = CompletableFuture.supplyAsync(() -> writer.send(NotificationJobKind.NOTIFY, id, emitter, SseEmitter.event()));
        assertThat(wire.waiting.await(1, TimeUnit.SECONDS)).isTrue();
        return result;
    }

    private static final class ReadyStream extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch waiting = new CountDownLatch(1);
        volatile boolean ready;
        WriteListener listener;
        @Override public boolean isReady() { if (!ready) waiting.countDown(); return ready; }
        @Override public void setWriteListener(WriteListener listener) { this.listener = listener; }
        @Override public void write(int value) { assertThat(ready).isTrue(); bytes.write(value); }
    }
}
