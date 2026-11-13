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
import com.catchtable.waiting.dto.WaitingCallNotification;
import com.catchtable.waiting.dto.WaitingCallResponse;
import com.catchtable.waiting.dto.WaitingDeferResponse;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.dto.WaitingRecallResponse;
import com.catchtable.waiting.dto.WaitingPolicyResponse;
import com.catchtable.waiting.dto.WaitingEnterResponse;
import com.catchtable.waiting.dto.WaitingTerminationResponse;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.dto.WaitingEventSnapshot;
import com.catchtable.waiting.dto.WaitingEventReference;
import com.catchtable.waiting.entity.WaitingDeferHistory;
import com.catchtable.waiting.entity.WaitingEndHistory;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import com.catchtable.waiting.repository.WaitingDeferHistoryRepository;
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
    private final WaitingEventRecorder eventRecorder;
    private final WaitingEndHistoryRepository historyRepository;
    private final WaitingDeferHistoryRepository deferHistoryRepository;
    private final StoreRepository storeRepository;
    private final StoreTableRepository storeTableRepository;
    private final MemberRepository memberRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public List<OwnerWaitingResponse> getWaitings(Long memberId, Long storeId) {
        requireOwnerStore(memberId, storeId);
        List<StoreTable> tables = storeTableRepository.findAllByStoreIdOrderByTableNumberAsc(storeId);
        return waitingRepository.findAllByStoreIdAndWaitingDateAndStatusInOrderByQueueOrderAsc(
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
        var before = WaitingEventSnapshot.of(waiting);
        WaitingEndHistory history = waiting.cancelByOwner(reason, memberId, OffsetDateTime.now(clock));
        if (history == null) {
            return;
        }
        historyRepository.save(history);
        var event = eventRecorder.ended(history, before);
        publishTermination(waiting, event, NotificationType.WAITING_CANCELED,
                "점주가 웨이팅을 취소했습니다. 사유: " + waiting.getEndDetail());
    }

    @Transactional
    public WaitingTerminationResponse markNoShow(Long memberId, Long waitingId,
                                                WaitingEndReason reason, String detail) {
        Waiting waiting = findOwnerWaitingForUpdate(memberId, waitingId);
        var before = WaitingEventSnapshot.of(waiting);
        WaitingEndHistory history = waiting.markNoShow(reason, detail, memberId, OffsetDateTime.now(clock));
        if (history != null) {
            historyRepository.save(history);
            var event = eventRecorder.ended(history, before);
            if (WaitingStatus.ACTIVE.contains(history.getPreviousStatus())) {
                publishTermination(waiting, event, NotificationType.WAITING_EXPIRED,
                        "미응답·미착석으로 웨이팅이 종료되었습니다. 사유: " + waiting.getEndDetail());
            }
        }
        return WaitingTerminationResponse.from(waiting);
    }

    @Transactional
    public WaitingPolicyResponse openWaiting(Long memberId, Long storeId) {
        Store store = findOwnerStoreForUpdate(memberId, storeId);
        store.openWaiting();
        return WaitingPolicyResponse.from(store);
    }

    @Transactional
    public WaitingPolicyResponse closeWaiting(Long memberId, Long storeId) {
        Store store = findOwnerStoreForUpdate(memberId, storeId);
        store.closeWaiting();
        return WaitingPolicyResponse.from(store);
    }

    public WaitingPolicyResponse getWaitingPolicy(Long memberId, Long storeId) {
        return WaitingPolicyResponse.from(requireOwnerStore(memberId, storeId));
    }

    @Transactional
    public WaitingPolicyResponse updateWaitingPolicy(Long memberId, Long storeId, int callLimitMinutes) {
        Store store = findOwnerStoreForUpdate(memberId, storeId);
        store.changeWaitingCallLimit(callLimitMinutes);
        return WaitingPolicyResponse.from(store);
    }

    @Transactional
    public WaitingCallResponse callWaiting(Long memberId, Long waitingId) {
        Waiting waiting = findOwnerWaitingForUpdate(memberId, waitingId);
        Store store = waiting.getStore();
        var before = WaitingEventSnapshot.of(waiting);
        waiting.call(OffsetDateTime.now(clock), store.getWaitingCallLimitMinutes());
        var event = eventRecorder.record(waiting, waiting.getCallCount() == 1 ? "CALLED" : "CALLED_AGAIN",
                "call:" + waiting.getCallCount(), waiting.getCalledAt(), memberId, before);

        eventPublisher.publishEvent(WaitingCallNotification.of(waiting.getMember().getId(), waiting.getId(),
                waiting.getWaitNumber(), store.getName(), waiting.getCalledAt(), waiting.getCallExpiresAt()).withWaitingEvent(event));
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                "대기 목록이 변경되었습니다.").withWaitingEvent(event));
        eventPublisher.publishEvent(new WaitingQueueChangedEvent(store.getId(), waiting.getWaitingDate()).withWaitingEvent(event));
        return WaitingCallResponse.from(waiting);
    }

    // 순번·상태 불변이라 목록·순번 이벤트 없음
    @Transactional
    public WaitingRecallResponse recallWaiting(Long memberId, Long waitingId) {
        Waiting waiting = findOwnerWaitingForUpdate(memberId, waitingId);
        var before = WaitingEventSnapshot.of(waiting);
        waiting.recall(OffsetDateTime.now(clock));
        var event = eventRecorder.record(waiting, "CALL_RESENT", "resend:" + waiting.getRecallCount(),
                waiting.getLastRecalledAt(), memberId, before);

        eventPublisher.publishEvent(WaitingCallNotification.of(waiting.getMember().getId(), waiting.getId(),
                waiting.getWaitNumber(), waiting.getStore().getName(), waiting.getCalledAt(), waiting.getCallExpiresAt()).withWaitingEvent(event));
        return WaitingRecallResponse.from(waiting);
    }

    @Transactional
    public WaitingEnterResponse enterWaiting(Long memberId, Long waitingId, Long tableId) {
        Waiting waiting = findOwnerWaitingForUpdate(memberId, waitingId);
        StoreTable table = storeTableRepository.findByIdAndStoreId(tableId, waiting.getStore().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TABLE_NOT_FOUND));
        var before = WaitingEventSnapshot.of(waiting);
        if (waiting.enter(table, OffsetDateTime.now(clock))) {
            var event = eventRecorder.record(waiting, "SEATED", "seated", waiting.getEnteredAt(), memberId, before);
            Store store = waiting.getStore();
            eventPublisher.publishEvent(new CustomerNotificationEvent(
                    store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                    "대기 목록이 변경되었습니다.").withWaitingEvent(event));
            eventPublisher.publishEvent(new WaitingQueueChangedEvent(store.getId(), waiting.getWaitingDate()).withWaitingEvent(event));
        }
        return WaitingEnterResponse.from(waiting, table);
    }

    // 매장 → 웨이팅 순서로 잠금 (신청과 동일)
    @Transactional
    public WaitingDeferResponse deferWaiting(Long memberId, Long waitingId) {
        requireOwner(memberId);
        Long storeId = waitingRepository.findStoreIdByIdAndOwnerId(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));
        Waiting waiting = waitingRepository.findByIdAndOwnerIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));
        waiting.validateDeferrable();
        Waiting next = waitingRepository.findActiveBehindForUpdate(
                        storeId, waiting.getWaitingDate(), waiting.getQueueOrder()).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_DEFERRABLE));
        int beforePosition = (int) waitingRepository.countByStoreIdAndWaitingDateAndStatusInAndQueueOrderLessThan(
                storeId, waiting.getWaitingDate(), WaitingStatus.ACTIVE, waiting.getQueueOrder()) + 1;
        // 맞바꾼 뒤 순번 (next 자리 앞 진행 중 팀 수: 나 대신 next가 들어옴)
        int afterPosition = (int) waitingRepository.countByStoreIdAndWaitingDateAndStatusInAndQueueOrderLessThan(
                storeId, waiting.getWaitingDate(), WaitingStatus.ACTIVE, next.getQueueOrder()) + 1;

        var before = WaitingEventSnapshot.of(waiting);
        var nextBefore = WaitingEventSnapshot.of(next);
        WaitingDeferHistory history = deferHistoryRepository.save(
                waiting.deferBehind(next, beforePosition, afterPosition, memberId, OffsetDateTime.now(clock)));
        var event = eventRecorder.deferred(history, before, next, nextBefore);

        eventPublisher.publishEvent(new CustomerNotificationEvent(
                store.getOwner().getId(), NotificationType.WAITING_CHANGED, storeId,
                "대기 목록이 변경되었습니다.").withWaitingEvent(event));
        eventPublisher.publishEvent(new WaitingQueueChangedEvent(storeId, waiting.getWaitingDate()).withWaitingEvent(event));
        return WaitingDeferResponse.of(waiting, history);
    }

    private void publishTermination(Waiting waiting, WaitingEventReference event, NotificationType type, String message) {
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                waiting.getMember().getId(), type, waiting.getId(), message).withWaitingEvent(event));
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                waiting.getStore().getOwner().getId(), NotificationType.WAITING_CHANGED,
                waiting.getStore().getId(), "대기 목록이 변경되었습니다.").withWaitingEvent(event));
        eventPublisher.publishEvent(new WaitingQueueChangedEvent(
                waiting.getStore().getId(), waiting.getWaitingDate()).withWaitingEvent(event));
    }

    private Waiting findOwnerWaitingForUpdate(Long memberId, Long waitingId) {
        requireOwner(memberId);
        return waitingRepository.findByIdAndOwnerIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));
    }

    // 신청과 같은 매장 행 락을 잡아, 종료가 커밋된 뒤의 신청은 WAITING_CLOSED로 거절되게 한다.
    private Store findOwnerStoreForUpdate(Long memberId, Long storeId) {
        requireOwner(memberId);
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (store.getOwner() == null || !store.getOwner().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
        return store;
    }

    private Store requireOwnerStore(Long memberId, Long storeId) {
        requireOwner(memberId);
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (store.getOwner() == null || !store.getOwner().getId().equals(memberId)) {
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
