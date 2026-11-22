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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

// NOTIFY와 REFRESH·QUEUE를 독립적으로 가져오고, 실제 연결 쓰기는 전용 풀에서 수행한다.
// 커밋 직후 신호(wake)로 바로 처리하고, 1초마다 안전망으로 한 번 더 확인한다.
@Slf4j
@Component
public class NotificationJobWorker {

    private static final int NOTIFY_CONCURRENCY = 2;
    static final Duration LEASE = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 3;
    static final Duration DELIVERY_RETRY_DELAY = Duration.ofSeconds(2);
    static final Duration RETENTION = Duration.ofDays(7);

    private final NotificationJobProcessor processor;
    private final NotificationJobSender sender;
    private final Clock clock;

    private final Lane notifyLane;
    private final Lane fanoutLane;
    private final boolean safetyNetEnabled;
    private final Semaphore notifySlots = new Semaphore(NOTIFY_CONCURRENCY);
    private final ExecutorService notifyDeliveries = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("notification-delivery-", 0).factory());

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
        notifyDeliveries.shutdownNow();
    }

    // 작업 1개 전송 + 결과 기록. 예외가 나면 PROCESSING으로 남고, lease가 지나면 다시 시도된다
    private void process(NotificationJob job) {
        try {
            Runnable checkClaim = new ClaimProgress(job);
            switch (job.getKind()) {
                case NOTIFY -> processor.complete(job.getId(), job.getAttemptCount(), sender.sendNotify(job, checkClaim));
                case REFRESH -> {
                    var result = sender.sendRefresh(job, checkClaim);
                    processor.completeFanout(job.getId(), job.getAttemptCount(), result.recipients(), result.sentCount());
                }
                case QUEUE -> {
                    var result = sender.sendQueue(job, checkClaim);
                    processor.completeFanout(job.getId(), job.getAttemptCount(), result.recipients(), result.sentCount());
                }
            }
        } catch (NotificationJobSender.DeliveryRetry e) {
            // 같은 매장·회원의 다음 작업이 30초 lease 동안 막히지 않게 현재 시도의 lease만 줄인다.
            log.warn("알림 전송 기한 초과, {} 뒤 다시 시도 jobId={}, kind={}", DELIVERY_RETRY_DELAY, job.getId(), job.getKind());
            try {
                processor.renew(job, DELIVERY_RETRY_DELAY);
            } catch (RuntimeException ignored) {
                // 줄이지 못하면 원래 lease 뒤 다시 시도된다.
            }
        } catch (RuntimeException e) {
            log.warn("알림 작업 처리 실패, lease 뒤 다시 시도 jobId={}, kind={}", job.getId(), job.getKind(), e);
        }
    }

    // 실제 연결에 쓰기 직전에만 연장한다. 긴 순번 발송 도중 처리권을 잃으면 다음 수신자부터 중단한다.
    private final class ClaimProgress implements Runnable {
        private final NotificationJob job;
        private OffsetDateTime nextCheck;

        private ClaimProgress(NotificationJob job) {
            this.job = job;
            this.nextCheck = job.getLeaseUntil().minus(LEASE.dividedBy(2));
        }

        @Override
        public void run() {
            var now = OffsetDateTime.now(clock);
            if (now.isBefore(nextCheck)) return;
            if (!processor.renew(job, LEASE)) throw new IllegalStateException("알림 작업 처리권이 만료되었습니다.");
            nextCheck = now.plus(LEASE.dividedBy(2));
        }
    }

    // 같은 회원의 다음 작업은 SQL에서 앞선 작업이 끝날 때까지 제외한다.
    // 빈 슬롯 수만큼만 claim하여 전송 대기 중인 작업의 lease가 먼저 시작되지 않게 한다.
    private void dispatchNotify(NotificationJob job) {
        notifySlots.acquireUninterruptibly();
        try {
            notifyDeliveries.execute(() -> {
                try {
                    process(job);
                } finally {
                    notifySlots.release();
                    notifyLane.wake();
                }
            });
        } catch (RejectedExecutionException stopped) {
            notifySlots.release();
            throw stopped;
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
                boolean notify = kinds.contains(NotificationJobKind.NOTIFY);
                while (!executor.isShutdown()) {
                    int limit = notify ? notifySlots.availablePermits() : 1;
                    if (limit == 0) return;
                    var batch = processor.claim(kinds, limit, LEASE, MAX_ATTEMPTS);
                    if (!batch.advanced()) return;
                    if (notify) batch.jobs().forEach(NotificationJobWorker.this::dispatchNotify);
                    else batch.jobs().forEach(NotificationJobWorker.this::process);
                }
            } catch (RuntimeException e) {
                log.warn("알림 작업 가져오기 실패, 다음 확인 때 다시 시도 lane={}", name, e);
            }
        }
    }
}
