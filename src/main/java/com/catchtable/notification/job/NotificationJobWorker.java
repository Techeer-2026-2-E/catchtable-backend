package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobKind;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

// 전송 스레드 2개: NOTIFY 전용 / REFRESH·QUEUE 전용.
// 커밋 직후 신호(wake)로 바로 처리하고, 1초마다 안전망으로 한 번 더 확인한다.
@Slf4j
@Component
public class NotificationJobWorker {

    static final int BATCH_SIZE = 100;
    static final Duration LEASE = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 3;
    static final Duration RETENTION = Duration.ofDays(7);

    private final NotificationJobProcessor processor;
    private final NotificationJobSender sender;
    private final Clock clock;

    private final Lane notifyLane;
    private final Lane fanoutLane;
    private final boolean safetyNetEnabled;

    public NotificationJobWorker(NotificationJobProcessor processor, NotificationJobSender sender, Clock clock,
                                 @Value("${notification.job.safety-net-enabled:true}") boolean safetyNetEnabled) {
        this.processor = processor;
        this.sender = sender;
        this.clock = clock;
        this.safetyNetEnabled = safetyNetEnabled;
        this.notifyLane = new Lane("notification-notify", Set.of(NotificationJobKind.NOTIFY));
        this.fanoutLane = new Lane("notification-fanout", Set.of(NotificationJobKind.REFRESH, NotificationJobKind.QUEUE));
    }

    // 커밋 직후 호출 (요청 스레드). 표시만 하고 바로 돌아간다
    public void wake(NotificationJobKind kind) {
        (kind == NotificationJobKind.NOTIFY ? notifyLane : fanoutLane).wake();
    }

    // 안전망: 신호가 빠졌거나 재시작 전에 남은 작업, 멈춘 작업을 1초 안에 처리
    @Scheduled(fixedDelay = 1000)
    public void safetyNet() {
        if (!safetyNetEnabled) {
            return; // 테스트에서만 끔: 다른 테스트 컨텍스트가 job을 가져가지 않게
        }
        notifyLane.wake();
        fanoutLane.wake();
    }

    // 끝난 지 7일 지난 작업 정리
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        int deleted = processor.deleteFinishedBefore(OffsetDateTime.now(clock).minus(RETENTION));
        if (deleted > 0) {
            log.info("알림 작업 정리: {}건 삭제", deleted);
        }
    }

    @PreDestroy
    public void shutdown() {
        notifyLane.executor.shutdownNow();
        fanoutLane.executor.shutdownNow();
    }

    // 작업 1개 전송 + 결과 기록. 예외가 나면 PROCESSING으로 남고, lease가 지나면 다시 시도된다
    private void process(NotificationJob job) {
        try {
            switch (job.getKind()) {
                case NOTIFY -> processor.complete(job.getId(), sender.sendNotify(job));
                case REFRESH -> {
                    var result = sender.sendRefresh(job);
                    processor.completeFanout(job.getId(), result.recipients(), result.sentCount());
                }
                case QUEUE -> {
                    var result = sender.sendQueue(job);
                    processor.completeFanout(job.getId(), result.recipients(), result.sentCount());
                }
            }
        } catch (RuntimeException e) {
            log.warn("알림 작업 처리 실패, lease 뒤 다시 시도 jobId={}, kind={}", job.getId(), job.getKind(), e);
        }
    }

    // 전송 스레드 하나. 동시에 하나만 돌고, 도는 중에 온 신호는 pending으로 남겨 끝난 뒤 한 번 더 확인한다
    private final class Lane {
        private final String name;
        private final Set<NotificationJobKind> kinds;
        private final ExecutorService executor;
        private final AtomicBoolean pending = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();

        private Lane(String name, Set<NotificationJobKind> kinds) {
            this.name = name;
            this.kinds = kinds;
            this.executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name(name).daemon().factory());
        }

        void wake() {
            pending.set(true);
            schedule();
        }

        // 이미 돌고 있으면 새로 넣지 않음 (실행 대기열이 쌓이지 않게)
        private void schedule() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException e) {
                running.set(false); // 종료 중
            }
        }

        private void drain() {
            try {
                while (pending.getAndSet(false)) {
                    drainOnce();
                }
            } finally {
                running.set(false);
                if (pending.get()) {
                    schedule(); // running을 내리는 사이에 온 신호
                }
            }
        }

        // 가져갈 작업이 없을 때까지 묶음으로 가져와 보낸다
        private void drainOnce() {
            try {
                List<NotificationJob> jobs;
                while (!(jobs = processor.claim(kinds, BATCH_SIZE, LEASE, MAX_ATTEMPTS)).isEmpty()) {
                    jobs.forEach(NotificationJobWorker.this::process);
                }
            } catch (RuntimeException e) {
                log.warn("알림 작업 가져오기 실패, 다음 확인 때 다시 시도 lane={}", name, e);
            }
        }
    }
}
