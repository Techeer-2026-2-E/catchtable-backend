package com.catchtable.waiting.service;

// 고객 웨이팅 전용 서비스


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.waiting.dto.WaitingPositionResponse;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitingQueryService {

    private final WaitingRepository waitingRepository;

    public WaitingPositionResponse getMyWaiting(Long memberId, Long waitingId)
    {
        Waiting waiting=waitingRepository.findByIdAndMemberId(waitingId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        if(!WaitingStatus.ACTIVE.contains(waiting.getStatus()))
        {
            return WaitingPositionResponse.of(waiting,null);
        }

        long teamsAhead=
                waitingRepository.countByStoreIdAndWaitingDateAndStatusInAndWaitNumberLessThan(
                        waiting.getStore().getId(), waiting.getWaitingDate(),
                        WaitingStatus.ACTIVE, waiting.getWaitNumber());
                return WaitingPositionResponse.of(waiting, teamsAhead);
    }
}
