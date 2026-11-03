package com.catchtable.reservation.dto;

import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;

import java.time.OffsetDateTime;

/** 방문 확인·완료 시각은 처리 전 null이며, completedAt은 시스템의 자동 완료 처리 시각이다. */
public record ReservationResponse(
        Long reservationId,
        Long storeId,
        String storeName,
        Long tableId,
        OffsetDateTime reservationStartAt,
        OffsetDateTime reservationEndAt,
        int partySize,
        ReservationStatus status,
        OffsetDateTime requestedAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        CancellationActor cancellationActor,
        String cancellationReason,
        OffsetDateTime checkedInAt,
        OffsetDateTime completedAt
) {
    public static ReservationResponse from(Reservation reservation, OffsetDateTime now) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getStore().getId(),
                reservation.getStore().getName(),
                reservation.getTableId(),
                reservation.getReservationStartAt(),
                reservation.getReservationEndAt(),
                reservation.getPartySize(),
                reservation.getStatusAt(now),
                reservation.getRequestedAt(),
                reservation.getConfirmedAt(),
                reservation.getCancelledAt(),
                reservation.getCancellationActor(),
                reservation.getCancellationReason(),
                reservation.getCheckedInAt(),
                reservation.getCompletedAt()
        );
    }
}
