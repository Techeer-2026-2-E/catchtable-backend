package com.catchtable.history.service;

import com.catchtable.history.dto.HistoryItemResponse;
import com.catchtable.history.dto.HistoryStatus;
import com.catchtable.history.dto.HistoryType;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 고객 본인의 예약·웨이팅 내역을 합쳐서 돌려준다.
 * 고객 한 명의 내역은 많지 않아 페이징 없이 메모리에서 합쳐 정렬한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryService {

    // 진행 중 먼저. 진행 중은 가까운 순, 종료는 최신순
    private static final Comparator<HistoryItemResponse> ORDER =
            Comparator.comparing(HistoryItemResponse::active).reversed()
                    .thenComparing((a, b) -> a.active()
                            ? a.sortAt().compareTo(b.sortAt())
                            : b.sortAt().compareTo(a.sortAt()));

    private final ReservationRepository reservationRepository;
    private final WaitingRepository waitingRepository;

    // type·status가 null이면 전체
    public List<HistoryItemResponse> getHistories(Long memberId, HistoryType type, HistoryStatus status) {
        List<HistoryItemResponse> items = new ArrayList<>();

        if (type == null || type == HistoryType.RESERVATION) {
            reservationRepository.findAllByMember_IdAndStatusIn(memberId, reservationStatuses(status))
                    .forEach(r -> items.add(HistoryItemResponse.from(r)));
        }
        if (type == null || type == HistoryType.WAITING) {
            waitingRepository.findAllByMemberIdAndStatusIn(memberId, waitingStatuses(status))
                    .forEach(w -> items.add(HistoryItemResponse.from(w)));
        }

        items.sort(ORDER);
        return items;
    }

    private static Set<ReservationStatus> reservationStatuses(HistoryStatus status) {
        if (status == null) return EnumSet.allOf(ReservationStatus.class);
        return status == HistoryStatus.ACTIVE
                ? ReservationStatus.ACTIVE
                : EnumSet.complementOf(EnumSet.copyOf(ReservationStatus.ACTIVE));
    }

    private static Set<WaitingStatus> waitingStatuses(HistoryStatus status) {
        if (status == null) return EnumSet.allOf(WaitingStatus.class);
        return status == HistoryStatus.ACTIVE
                ? WaitingStatus.ACTIVE
                : EnumSet.complementOf(EnumSet.copyOf(WaitingStatus.ACTIVE));
    }
}
