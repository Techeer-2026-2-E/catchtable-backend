package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.entity.NotificationJobResult;
import com.catchtable.notification.entity.NotificationJobStatus;
import com.catchtable.notification.repository.NotificationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// 작업 상태를 바꾸는 짧은 TX 모음. 네트워크 전송은 여기서 하지 않는다 (DB 연결을 오래 잡지 않게)
@Component
@RequiredArgsConstructor
public class NotificationJobProcessor {

    private final NotificationJobRepository repository;
    private final Clock clock;

    // 가져갈 작업을 잠그고 PROCESSING으로 바꾼 뒤 커밋. 반환된 작업만 보내면 된다
    @Transactional
    public List<NotificationJob> claim(Collection<NotificationJobKind> kinds, int limit,
                                       Duration lease, int maxAttempts) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> kindNames = kinds.stream().map(Enum::name).toList();
        List<NotificationJob> claimed = new ArrayList<>();

        for (NotificationJob job : repository.lockClaimable(kindNames, now, limit)) {
            // 멈췄던 REFRESH·QUEUE인데 같은 매장에 새 PENDING이 있으면, 그쪽이 최신 상태를 보낸다
            if (job.getStatus() == NotificationJobStatus.PROCESSING && hasNewerPending(job)) {
                job.merge(now);
                continue;
            }
            job.claim(now, lease);
            if (job.hasExceededAttempts(maxAttempts)) {
                job.fail(now);
                continue;
            }
            claimed.add(job);
        }
        return claimed;
    }

    // NOTIFY 결과 기록
    @Transactional
    public void complete(Long jobId, NotificationJobResult result) {
        repository.findById(jobId)
                .filter(job -> job.getStatus() == NotificationJobStatus.PROCESSING)
                .ifPresent(job -> job.complete(result, OffsetDateTime.now(clock)));
    }

    // REFRESH·QUEUE 결과 기록
    @Transactional
    public void completeFanout(Long jobId, int recipients, int sentCount) {
        repository.findById(jobId)
                .filter(job -> job.getStatus() == NotificationJobStatus.PROCESSING)
                .ifPresent(job -> job.completeFanout(recipients, sentCount, OffsetDateTime.now(clock)));
    }

    // 끝난 지 오래된 작업 삭제
    @Transactional
    public int deleteFinishedBefore(OffsetDateTime threshold) {
        return repository.deleteFinishedBefore(threshold);
    }

    private boolean hasNewerPending(NotificationJob job) {
        return switch (job.getKind()) {
            case REFRESH -> repository.existsByKindAndStoreIdAndStatus(
                    NotificationJobKind.REFRESH, job.getStoreId(), NotificationJobStatus.PENDING);
            case QUEUE -> repository.existsByKindAndStoreIdAndWaitingDateAndStatus(
                    NotificationJobKind.QUEUE, job.getStoreId(), job.getWaitingDate(),
                    NotificationJobStatus.PENDING);
            case NOTIFY -> false;
        };
    }
}
