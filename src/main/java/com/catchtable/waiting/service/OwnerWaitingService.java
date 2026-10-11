package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.service.NotificationService;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import com.catchtable.waiting.dto.OwnerWaitingResponse;
import com.catchtable.waiting.dto.WaitingTerminationResponse;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndHistory;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerWaitingService {

    private final WaitingRepository waitingRepository;
    private final WaitingEndHistoryRepository historyRepository;
    private final StoreRepository storeRepository;
    private final StoreTableRepository storeTableRepository;
    private final MemberRepository memberRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public List<OwnerWaitingResponse> getWaitings(Long memberId, Long storeId) {
        requireOwnerStore(memberId, storeId);
        List<StoreTable> tables = storeTableRepository.findAllByStoreIdOrderByTableNumberAsc(storeId);
        return waitingRepository.findAllByStoreIdAndWaitingDateAndStatusInOrderByWaitNumberAsc(
                        storeId, LocalDate.now(clock), WaitingStatus.ACTIVE).stream()
                .map(waiting -> OwnerWaitingResponse.from(waiting, tables))
                .toList();
    }

    public SseEmitter subscribe(Long memberId, Long storeId) {
        requireOwnerStore(memberId, storeId);
        // shortcut: 단일 서버의 메모리 SSE를 재사용한다. 다중 인스턴스 배포 시 공유 전달이 필요하다.
        return notificationService.subscribe(memberId);
    }

    @Transactional
    public void cancelWaiting(Long memberId, Long waitingId, String reason) {
        Waiting waiting = findOwnerWaitingForUpdate(memberId, waitingId);
        WaitingEndHistory history = waiting.cancelByOwner(reason, memberId, OffsetDateTime.now(clock));
        if (history == null) {
            return;
        }
        historyRepository.save(history);
        publishTermination(waiting, NotificationType.WAITING_CANCELED,
                "점주가 웨이팅을 취소했습니다. 사유: " + waiting.getEndDetail());
    }

    @Transactional
    public WaitingTerminationResponse markNoShow(Long memberId, Long waitingId,
                                                WaitingEndReason reason, String detail) {
        Waiting waiting = findOwnerWaitingForUpdate(memberId, waitingId);
        WaitingEndHistory history = waiting.markNoShow(reason, detail, memberId, OffsetDateTime.now(clock));
        if (history != null) {
            historyRepository.save(history);
            if (WaitingStatus.ACTIVE.contains(history.getPreviousStatus())) {
                publishTermination(waiting, NotificationType.WAITING_EXPIRED,
                        "미응답·미착석으로 웨이팅이 종료되었습니다. 사유: " + waiting.getEndDetail());
            }
        }
        return WaitingTerminationResponse.from(waiting);
    }

    private void publishTermination(Waiting waiting, NotificationType type, String message) {
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                waiting.getMember().getId(), type, waiting.getId(), message));
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                waiting.getStore().getOwner().getId(), NotificationType.WAITING_CHANGED,
                waiting.getStore().getId(), "대기 목록이 변경되었습니다."));
    }

    private Waiting findOwnerWaitingForUpdate(Long memberId, Long waitingId) {
        requireOwner(memberId);
        return waitingRepository.findByIdAndOwnerIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));
    }

    private Store requireOwnerStore(Long memberId, Long storeId) {
        requireOwner(memberId);
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (!store.getOwner().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
        return store;
    }

    private void requireOwner(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getUserType() != UserType.OWNER) {
            throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
        }
    }
}
