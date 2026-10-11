package com.catchtable.history.service;

import com.catchtable.history.dto.HistoryItemResponse;
import com.catchtable.history.dto.HistoryStatus;
import com.catchtable.history.dto.HistoryType;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-10T12:00:00+09:00");

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final WaitingRepository waitingRepository = mock(WaitingRepository.class);
    private final HistoryService historyService = new HistoryService(reservationRepository, waitingRepository);

    private final Store store = store();

    @Test
    @DisplayName("진행 중이 먼저 오고, 진행 중은 가까운 순·종료는 최신순으로 정렬한다")
    void ordersActiveFirstThenEndedLatest() {
        Reservation farReservation = reservation(1L, ReservationStatus.CONFIRMED, NOW.plusDays(5));
        Reservation nearReservation = reservation(2L, ReservationStatus.CONFIRMED, NOW.plusDays(1));
        Reservation oldCompleted = reservation(3L, ReservationStatus.COMPLETED, NOW.minusDays(10));
        Reservation recentCancelled = reservation(4L, ReservationStatus.CANCELLED, NOW.minusDays(2));
        Waiting todayWaiting = waiting(10L, WaitingStatus.WAITING, NOW.minusMinutes(30));
        Waiting seatedWaiting = waiting(11L, WaitingStatus.SEATED, NOW.minusDays(5));

        when(reservationRepository.findAllByMember_IdAndStatusIn(eq(MEMBER_ID), anyCollection()))
                .thenReturn(List.of(farReservation, oldCompleted, nearReservation, recentCancelled));
        when(waitingRepository.findAllByMemberIdAndStatusIn(eq(MEMBER_ID), anyCollection()))
                .thenReturn(List.of(seatedWaiting, todayWaiting));

        List<HistoryItemResponse> result = historyService.getHistories(MEMBER_ID, null, null);

        assertThat(result).extracting(HistoryItemResponse::type, HistoryItemResponse::id)
                .containsExactly(
                        tuple(HistoryType.WAITING, 10L),     // 진행 중, 오늘 신청
                        tuple(HistoryType.RESERVATION, 2L),  // 진행 중, 내일 방문
                        tuple(HistoryType.RESERVATION, 1L),  // 진행 중, 5일 뒤 방문
                        tuple(HistoryType.RESERVATION, 4L),  // 종료, 2일 전
                        tuple(HistoryType.WAITING, 11L),     // 종료, 5일 전
                        tuple(HistoryType.RESERVATION, 3L)); // 종료, 10일 전
        assertThat(result).extracting(HistoryItemResponse::active)
                .containsExactly(true, true, true, false, false, false);
    }

    @Test
    @DisplayName("유형을 지정하면 다른 유형은 조회하지 않는다")
    void filtersByType() {
        Reservation reservation = reservation(1L, ReservationStatus.CONFIRMED, NOW.plusDays(1));
        when(reservationRepository.findAllByMember_IdAndStatusIn(eq(MEMBER_ID), anyCollection()))
                .thenReturn(List.of(reservation));

        List<HistoryItemResponse> result = historyService.getHistories(MEMBER_ID, HistoryType.RESERVATION, null);

        assertThat(result).extracting(HistoryItemResponse::type).containsOnly(HistoryType.RESERVATION);
        verify(waitingRepository, never()).findAllByMemberIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("상태 필터는 진행 중/종료 상태 묶음으로 바꿔 조회한다")
    void mapsStatusFilterToStatuses() {
        historyService.getHistories(MEMBER_ID, null, HistoryStatus.ACTIVE);
        verify(reservationRepository).findAllByMember_IdAndStatusIn(eq(MEMBER_ID),
                argThat(statuses -> sameElements(statuses, Set.of(ReservationStatus.CONFIRMED))));
        verify(waitingRepository).findAllByMemberIdAndStatusIn(eq(MEMBER_ID),
                argThat(statuses -> sameElements(statuses,
                        Set.of(WaitingStatus.WAITING, WaitingStatus.CALLED, WaitingStatus.CONFIRMED))));

        historyService.getHistories(MEMBER_ID, null, HistoryStatus.ENDED);
        verify(reservationRepository).findAllByMember_IdAndStatusIn(eq(MEMBER_ID),
                argThat(statuses -> sameElements(statuses, Set.of(
                        ReservationStatus.COMPLETED, ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW))));
        verify(waitingRepository).findAllByMemberIdAndStatusIn(eq(MEMBER_ID),
                argThat(statuses -> sameElements(statuses, Set.of(
                        WaitingStatus.SEATED, WaitingStatus.CANCELED, WaitingStatus.EXPIRED))));
    }

    private static boolean sameElements(Collection<?> actual, Set<?> expected) {
        return actual != null && actual.size() == expected.size() && actual.containsAll(expected);
    }

    private Reservation reservation(Long id, ReservationStatus status, OffsetDateTime startAt) {
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(id);
        when(reservation.getStore()).thenReturn(store);
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.getPartySize()).thenReturn(2);
        when(reservation.getReservationStartAt()).thenReturn(startAt);
        when(reservation.getReservationEndAt()).thenReturn(startAt.plusHours(2));
        when(reservation.getRequestedAt()).thenReturn(startAt.minusDays(3));
        return reservation;
    }

    private Waiting waiting(Long id, WaitingStatus status, OffsetDateTime createdAt) {
        Waiting waiting = mock(Waiting.class);
        when(waiting.getId()).thenReturn(id);
        when(waiting.getStore()).thenReturn(store);
        when(waiting.getStatus()).thenReturn(status);
        when(waiting.getPartyCount()).thenReturn(3);
        when(waiting.getWaitingDate()).thenReturn(LocalDate.from(createdAt));
        when(waiting.getWaitNumber()).thenReturn(7);
        when(waiting.getCreatedAt()).thenReturn(createdAt);
        return waiting;
    }

    private static Store store() {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(1L);
        when(store.getName()).thenReturn("목 식당");
        return store;
    }
}
