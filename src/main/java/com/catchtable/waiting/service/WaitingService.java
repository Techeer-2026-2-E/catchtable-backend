package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.dto.WaitingRespondResponse;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.dto.WaitingResponse;
import com.catchtable.waiting.dto.WaitingTerminationResponse;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndHistory;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitingService {

    private final WaitingRepository waitingRepository;
    private final WaitingEndHistoryRepository historyRepository;
    private final StoreRepository storeRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // 원격 웨이팅 신청
    @Transactional
    public WaitingCreateResult createWaiting(Long memberId, Long storeId, int partyCount) {
        // 1. 매장 조회 + 락 (없으면 404)
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        // 오늘 날짜
        LocalDate today = LocalDate.now(clock);

        // 2. 진행 중 웨이팅 확인 (마감 체크보다 먼저 → 재시도 시 항상 같은 결과)
        Optional<Waiting> existing = waitingRepository
                .findFirstByMemberIdAndStoreIdAndWaitingDateAndStatusIn(
                        memberId, storeId, today, WaitingStatus.ACTIVE);

        if (existing.isPresent()) {
            Waiting waiting = existing.get();
            // 인원 다름 → 중복 신청 에러 (409)
            if (waiting.getPartyCount() != partyCount) {
                throw new BusinessException(ErrorCode.DUPLICATE_ACTIVE_WAITING);
            }
            // 인원 같음 → 재시도, 기존 웨이팅 반환
            return new WaitingCreateResult(toResponse(waiting, storeId), false);
        }

        // 3. 접수 마감 체크
        store.validateWaitingAvailable();

        // 회원 조회
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 4. 대기번호 발급 (오늘 최대값 + 1, 락 안이라 안전)
        int nextNumber = waitingRepository.findMaxWaitNumber(storeId, today) + 1;

        // 5. 저장
        Waiting saved = waitingRepository.save(
                Waiting.create(member, store, today, partyCount, nextNumber));

        eventPublisher.publishEvent(new CustomerNotificationEvent(
                store.getOwner().getId(), NotificationType.WAITING_CHANGED, storeId,
                "대기 목록이 변경되었습니다."));

        return new WaitingCreateResult(toResponse(saved, storeId), true);
    }

    // 호출 응답
    @Transactional
    public WaitingRespondResponse respondToCall(Long memberId, Long waitingId) {
        Waiting waiting = waitingRepository.findByIdAndMemberIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        // 첫 응답만 점주 알림
        if (waiting.respond(OffsetDateTime.now(clock))) {
            Store store = waiting.getStore();
            eventPublisher.publishEvent(new CustomerNotificationEvent(
                    store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                    "대기 목록이 변경되었습니다."));
        }
        return WaitingRespondResponse.from(waiting);
    }

    // 고객 취소
    @Transactional
    public WaitingTerminationResponse cancelWaiting(Long memberId, Long waitingId) {
        Waiting waiting = waitingRepository.findByIdAndMemberIdForUpdate(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        WaitingEndHistory history = waiting.cancelByCustomer(memberId, OffsetDateTime.now(clock));
        // 처음 취소만 이력·알림
        if (history != null) {
            historyRepository.save(history);
            Store store = waiting.getStore();
            eventPublisher.publishEvent(new CustomerNotificationEvent(
                    store.getOwner().getId(), NotificationType.WAITING_CHANGED, store.getId(),
                    "대기 목록이 변경되었습니다."));
            eventPublisher.publishEvent(new WaitingQueueChangedEvent(store.getId(), waiting.getWaitingDate()));
        }
        return WaitingTerminationResponse.from(waiting);
    }

    // 응답 변환 (앞 팀 수 계산 포함)
    private WaitingResponse toResponse(Waiting waiting, Long storeId) {
        long teamsAhead = waitingRepository.countByStoreIdAndWaitingDateAndStatusInAndWaitNumberLessThan(
                storeId, waiting.getWaitingDate(), WaitingStatus.ACTIVE, waiting.getWaitNumber());
        return WaitingResponse.of(waiting, storeId, teamsAhead);
    }
}
