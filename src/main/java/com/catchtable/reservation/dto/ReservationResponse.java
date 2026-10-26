package com.catchtable.reservation.dto;

import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;

import java.time.OffsetDateTime;

/** 취소 관련 필드는 취소 전에는 null이다. 결제 대기 중에는 confirmedAt도 null이다. */
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
        String cancellationReason
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
                reservation.getCancellationReason()
        );
    }
}
