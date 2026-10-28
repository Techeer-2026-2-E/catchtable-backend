package com.catchtable.waiting.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.store.entity.Store;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndHistory;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;

// 호출 후 응답·입장 기한 자동 만료
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingExpiryScheduler {

    private final WaitingRepository waitingRepository;
    private final WaitingEndHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 만료 건수 반환
    @Scheduled(initialDelayString = "${waiting.expiry-interval-ms:60000}",
            fixedDelayString = "${waiting.expiry-interval-ms:60000}")
    public int expireCalledWaitings() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int expired = 0;
        for (Long waitingId : waitingRepository.findCallExpiredIds(now)) {
            // 건별 트랜잭션
            try {
                if (Boolean.TRUE.equals(transactions.execute(transaction -> expire(waitingId, now)))) {
                    expired++;
                }
            } catch (RuntimeException exception) {
                log.warn("웨이팅 자동 만료 실패 waitingId={}", waitingId, exception);
            }
        }
        return expired;
    }

    private boolean expire(Long waitingId, OffsetDateTime now) {
        Waiting waiting = waitingRepository.findByIdForUpdate(waitingId).orElse(null);
        // 잠금 후 상태·기한 재확인
        WaitingEndHistory history = waiting == null ? null : waiting.expire(now);
        if (history == null) {
            return false;
        }
        historyRepository.save(history);
        Store store = waiting.getStore();
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                waiting.getMember().getId(), NotificationType.WAITING_EXPIRED, waiting.getId(),
                "호출 후 응답·입장 기한이 지나 웨이팅이 종료되었습니다."));
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                "대기 목록이 변경되었습니다."));
        eventPublisher.publishEvent(new WaitingQueueChangedEvent(store.getId(), waiting.getWaitingDate()));
        return true;
    }
}
