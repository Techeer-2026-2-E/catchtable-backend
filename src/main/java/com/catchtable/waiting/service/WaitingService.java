package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.service.BusinessHourService;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.dto.WaitingRespondResponse;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.dto.WaitingResponse;
import com.catchtable.waiting.dto.WaitingTerminationResponse;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.dto.WaitingEventSnapshot;
import com.catchtable.waiting.dto.WaitingEventReference;
import com.catchtable.waiting.entity.WaitingEndHistory;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import com.catchtable.waiting.repository.WaitingReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitingService {

    private final WaitingRepository waitingRepository;
    private final WaitingReceiptRepository receipts;
    private final WaitingEventRecorder eventRecorder;
    private final WaitingEndHistoryRepository historyRepository;
    private final StoreRepository storeRepository;
    private final MemberRepository memberRepository;
    private final BusinessHourService businessHourService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // 원격 웨이팅 신청
    @Transactional
    public WaitingCreateResult createWaiting(Long memberId, Long storeId, int partyCount) {
        // 1. 매장 조회 + 락 (없으면 404)
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        LocalDateTime at = LocalDateTime.now(clock);
        if (receipts.openForLockedStore(storeId, at.toLocalDate())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return registerLocked(memberId, store, partyCount, at);
    }

    // Called within the submission transaction with the store write lock already held.
    WaitingCreateResult registerLocked(Long memberId, Store store, int partyCount, LocalDateTime at) {
        Long storeId = store.getId();
        LocalDate today = at.toLocalDate();
        var existing = existingRegistration(memberId, storeId, today, partyCount);
        if (existing.isPresent()) {
            return existing.get();
        }
        validateIntakeOpen(store, at);
        receipts.reserveLegacyRegistrationSlot(today);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        int next = waitingRepository.findMaxWaitNumber(storeId, today) + 1;
        Waiting saved = registerNumber(member, store, today, partyCount, next);
        return new WaitingCreateResult(toResponse(saved, storeId), true);
    }

    // The receipt FK already guarantees this member exists, including after the store closes.
    Long registerAcceptedNumber(long memberId, Store store, LocalDate day, int party, int next) {
        return registerNumber(memberRepository.getReferenceById(memberId), store, day, party, next).getId();
    }

    private Waiting registerNumber(Member member, Store store, LocalDate today, int partyCount, int nextNumber) {
        Long memberId = member.getId();
        Long storeId = store.getId();
        Waiting saved = waitingRepository.save(
                Waiting.create(member, store, today, partyCount, nextNumber));

        var event = eventRecorder.record(saved, "REGISTERED", "registered", OffsetDateTime.now(clock), memberId, null);
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                store.getOwner().getId(), NotificationType.WAITING_CHANGED, storeId,
                "대기 목록이 변경되었습니다.").withWaitingEvent(event));

        return saved;
    }

    Optional<WaitingCreateResult> existingRegistration(Long memberId, Long storeId, LocalDate day, int partyCount) {
        return waitingRepository.findFirstByMemberIdAndStoreIdAndWaitingDateAndStatusIn(
                memberId, storeId, day, WaitingStatus.ACTIVE).map(waiting -> {
            if (waiting.getPartyCount() != partyCount) {
                throw new BusinessException(ErrorCode.DUPLICATE_ACTIVE_WAITING);
            }
            return new WaitingCreateResult(toResponse(waiting, storeId), false);
        });
    }

    void validateIntakeOpen(Store store, LocalDateTime at) {
        intakeClosedReason(store, at).ifPresent(reason -> { throw new BusinessException(reason); });
    }

    Optional<ErrorCode> intakeClosedReason(Store store, LocalDateTime at) {
        try {
            store.validateWaitingAvailable();
        } catch (BusinessException closed) {
            return Optional.of(closed.getErrorCode());
        }
        return businessHourService.isOpen(store.getId(), at)
                ? Optional.empty() : Optional.of(ErrorCode.WAITING_OUTSIDE_BUSINESS_HOURS);
    }

    // 호출 응답
    @Transactional
    public WaitingRespondResponse respondToCall(Long memberId, Long waitingId) {
        Waiting waiting = waitingRepository.findByIdAndMemberIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        var before = WaitingEventSnapshot.of(waiting);
        // 첫 응답만 점주 알림
        if (waiting.respond(OffsetDateTime.now(clock))) {
            var event = eventRecorder.record(waiting, "RESPONDED", "response:" + waiting.getCallCount(),
                    waiting.getRespondedAt(), memberId, before);
            Store store = waiting.getStore();
            eventPublisher.publishEvent(new CustomerNotificationEvent(
                    store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                    "대기 목록이 변경되었습니다.").withWaitingEvent(event));
        }
        return WaitingRespondResponse.from(waiting);
    }

    // 고객 취소
    @Transactional
    public WaitingTerminationResponse cancelWaiting(Long memberId, Long waitingId) {
        Waiting waiting = waitingRepository.findByIdAndMemberIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        var before = WaitingEventSnapshot.of(waiting);
        WaitingEndHistory history = waiting.cancelByCustomer(memberId, OffsetDateTime.now(clock));
        // 처음 취소만 이력·알림
        if (history != null) {
            historyRepository.save(history);
            var event = eventRecorder.ended(history, before);
            Store store = waiting.getStore();
            eventPublisher.publishEvent(new CustomerNotificationEvent(
                    store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                    "대기 목록이 변경되었습니다.").withWaitingEvent(event));
            eventPublisher.publishEvent(new WaitingQueueChangedEvent(store.getId(), waiting.getWaitingDate()).withWaitingEvent(event));
        }
        return WaitingTerminationResponse.from(waiting);
    }

    // 응답 변환 (앞 팀 수 계산 포함)
    private WaitingResponse toResponse(Waiting waiting, Long storeId) {
        long teamsAhead = waitingRepository.countByStoreIdAndWaitingDateAndStatusInAndQueueOrderLessThan(
                storeId, waiting.getWaitingDate(), WaitingStatus.ACTIVE, waiting.getQueueOrder());
        return WaitingResponse.of(waiting, storeId, teamsAhead);
    }
}
