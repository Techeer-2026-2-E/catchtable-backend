package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SseConnectionWriterTest {
    @Test
    void wrappedServletTimeoutKeepsTheDeliveryUnconfirmed() throws IOException {
        var repo = new SseEmitterRepository();
        var writer = new SseConnectionWriter(repo, Duration.ofSeconds(1), 2);
        var emitter = mock(SseEmitter.class);
        repo.save(1L, emitter);
        doThrow(new IOException("ServletOutputStream", new SocketTimeoutException()))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try {
            assertThat(writer.send(NotificationJobKind.NOTIFY, 1L, emitter, SseEmitter.event()))
                    .isEqualTo(SseConnectionWriter.Outcome.RETRY);
            assertThat(repo.findAllByMemberId(1L)).isEmpty();
        } finally {
            writer.close();
        }
    }

    @Test
    void timedOutWritesKeepTheirSlotsAndOtherLaneCanStillDeliver() throws Exception {
        var connections = new SseEmitterRepository();
        var writer = new SseConnectionWriter(connections, Duration.ofMillis(200), 2);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(2);
        try {
            for (long member = 1; member <= 2; member++) {
                var emitter = mock(SseEmitter.class);
                connections.save(member, emitter);
                doAnswer(call -> {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 쓰기 시간 초과");
                    } finally {
                        ended.countDown();
                    }
                    return null;
                }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
                assertThat(writer.send(NotificationJobKind.QUEUE, member, emitter, SseEmitter.event()))
                        .isEqualTo(SseConnectionWriter.Outcome.RETRY);
                assertThat(connections.findAllByMemberId(member)).isEmpty();
            }
            var healthy = mock(SseEmitter.class);
            connections.save(3L, healthy);
            assertThat(writer.send(NotificationJobKind.QUEUE, 3L, healthy, SseEmitter.event()))
                    .isEqualTo(SseConnectionWriter.Outcome.RETRY);
            verifyNoInteractions(healthy);
            assertThat(connections.findAllByMemberId(3L)).containsExactly(healthy);
            assertThat(writer.send(NotificationJobKind.NOTIFY, 3L, healthy, SseEmitter.event()))
                    .isEqualTo(SseConnectionWriter.Outcome.SENT);
            verify(healthy).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            release.countDown();
            try {
                assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                writer.close();
            }
        }
    }
}
