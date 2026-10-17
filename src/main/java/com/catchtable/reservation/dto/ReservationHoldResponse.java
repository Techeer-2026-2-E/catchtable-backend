package com.catchtable.reservation.dto;

import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;

import java.time.Duration;
import java.time.OffsetDateTime;

/** 선점은 결제 대기 예약 자체이므로 holdId와 reservationId가 같다. */
public record ReservationHoldResponse(
        Long holdId,
        Long reservationId,
        Long storeId,
        Long tableId,
        OffsetDateTime reservationStartAt,
        OffsetDateTime reservationEndAt,
        int partySize,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime serverTime,
        long remainingSeconds
) {
    public static ReservationHoldResponse from(Reservation reservation, OffsetDateTime now) {
        ReservationStatus status = reservation.getStatusAt(now);
        long remaining = status == ReservationStatus.PENDING_PAYMENT
                ? Math.max(0, Duration.between(now, reservation.getHoldExpiresAt()).toSeconds()) : 0;
        return new ReservationHoldResponse(
                reservation.getId(), reservation.getId(), reservation.getStore().getId(),
                reservation.getTableId(), reservation.getReservationStartAt(), reservation.getReservationEndAt(),
                reservation.getPartySize(), status, reservation.getHoldExpiresAt(), now, remaining);
    }
}
