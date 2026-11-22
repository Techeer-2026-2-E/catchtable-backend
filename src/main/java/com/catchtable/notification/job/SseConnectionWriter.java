package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.repository.SseEmitterRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class SseConnectionWriter {
    enum Outcome { SENT, CLOSED, RETRY }

    private final SseEmitterRepository connections;
    private final long timeoutMillis;
    private final ThreadPoolExecutor notifyWrites;
    private final ThreadPoolExecutor fanoutWrites;

    public SseConnectionWriter(SseEmitterRepository connections,
                               @Value("${notification.job.write-timeout:500ms}") Duration timeout,
                               @Value("${notification.job.writes-per-lane:4}") int writesPerLane) {
        if (timeout.toMillis() < 1 || writesPerLane < 2) {
            throw new IllegalArgumentException("SSE 쓰기 제한 시간은 1ms 이상, 경로별 전송 수는 2개 이상이어야 합니다.");
        }
        this.connections = connections;
        this.timeoutMillis = timeout.toMillis();
        this.notifyWrites = pool("sse-notify-write-", writesPerLane);
        this.fanoutWrites = pool("sse-fanout-write-", writesPerLane);
    }

    private static ThreadPoolExecutor pool(String name, int limit) {
        return new ThreadPoolExecutor(0, limit, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon(true).name(name, 0).factory());
    }

    Outcome send(NotificationJobKind kind, long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        var result = new CompletableFuture<Outcome>();
        var pool = kind == NotificationJobKind.NOTIFY ? notifyWrites : fanoutWrites;
        try {
            pool.execute(() -> write(memberId, emitter, event, result));
        } catch (RejectedExecutionException saturated) {
            return Outcome.RETRY;
        }
        try {
            return result.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).get();
        } catch (InterruptedException interrupted) {
            if (result.completeExceptionally(interrupted)) connections.delete(memberId, emitter);
            Thread.currentThread().interrupt();
            return Outcome.RETRY;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof TimeoutException) {
                // 실제 쓰기는 아직 진행 중일 수 있다. 해당 연결만 제외하고 슬롯은 쓰기가 끝날 때 반환한다.
                connections.delete(memberId, emitter);
                return Outcome.RETRY;
            }
            throw new IllegalStateException("SSE 전송 처리 실패", failed.getCause());
        }
    }

    private void write(long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event,
                       CompletableFuture<Outcome> result) {
        try {
            emitter.send(event);
            if (!result.complete(Outcome.SENT)) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException closed) {
            connections.delete(memberId, emitter);
            result.complete(Outcome.CLOSED);
        } catch (RuntimeException failed) {
            result.completeExceptionally(failed);
        }
    }

    @PreDestroy
    public void close() {
        notifyWrites.shutdownNow();
        fanoutWrites.shutdownNow();
    }
}
