package com.catchtable.history.dto;

import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 예약·웨이팅 내역 한 건. 유형에 해당하지 않는 필드는 null이다.
 * (예약: reservationStartAt·reservationEndAt / 웨이팅: waitingDate·waitNumber)
 */
public record HistoryItemResponse(
        HistoryType type,
        Long id,
        Long storeId,
        String storeName,
        String status,
        boolean active,
        int partySize,
        OffsetDateTime reservationStartAt,
        OffsetDateTime reservationEndAt,
        LocalDate waitingDate,
        Integer waitNumber,
        OffsetDateTime requestedAt
) {
    public static HistoryItemResponse from(Reservation reservation) {
        return new HistoryItemResponse(
                HistoryType.RESERVATION,
                reservation.getId(),
                reservation.getStore().getId(),
                reservation.getStore().getName(),
                reservation.getStatus().name(),
                ReservationStatus.ACTIVE.contains(reservation.getStatus()),
                reservation.getPartySize(),
                reservation.getReservationStartAt(),
                reservation.getReservationEndAt(),
                null,
                null,
                reservation.getRequestedAt()
        );
    }

    public static HistoryItemResponse from(Waiting waiting) {
        return new HistoryItemResponse(
                HistoryType.WAITING,
                waiting.getId(),
                waiting.getStore().getId(),
                waiting.getStore().getName(),
                waiting.getStatus().name(),
                WaitingStatus.ACTIVE.contains(waiting.getStatus()),
                waiting.getPartyCount(),
                null,
                null,
                waiting.getWaitingDate(),
                waiting.getWaitNumber(),
                waiting.getCreatedAt()
        );
    }

    // 정렬 기준 시각: 예약은 방문 시각, 웨이팅은 신청 시각
    @JsonIgnore
    public OffsetDateTime sortAt() {
        return type == HistoryType.RESERVATION ? reservationStartAt : requestedAt;
    }
}
