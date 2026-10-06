package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.dto.WaitingResponse;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitingService {

    // 날짜 기준 시간대 (한국)
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final WaitingRepository waitingRepository;
    private final StoreRepository storeRepository;
    private final MemberRepository memberRepository;

    // 원격 웨이팅 신청
    @Transactional
    public WaitingCreateResult createWaiting(Long memberId, Long storeId, int partyCount) {
        // 1. 매장 조회 + 락 (없으면 404)
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        // 오늘 날짜
        LocalDate today = LocalDate.now(ZONE);

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

        return new WaitingCreateResult(toResponse(saved, storeId), true);
    }

    // 응답 변환 (앞 팀 수 계산 포함)
    private WaitingResponse toResponse(Waiting waiting, Long storeId) {
        long teamsAhead = waitingRepository.countByStoreIdAndWaitingDateAndStatusInAndWaitNumberLessThan(
                storeId, waiting.getWaitingDate(), WaitingStatus.ACTIVE, waiting.getWaitNumber());
        return WaitingResponse.of(waiting, storeId, teamsAhead);
    }
}