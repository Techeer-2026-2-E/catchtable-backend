package com.catchtable.reservation.dto;

import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;

import java.time.OffsetDateTime;

/** memberId와 고객 정보는 예약자 기준이다. 입장·취소·노쇼 관련 필드는 해당 처리 전까지 null이다. */
public record OwnerReservationResponse(
        Long reservationId,
        Long storeId,
        Long memberId,
        String customerName,
        String customerPhone,
        Long tableId,
        OffsetDateTime reservationStartAt,
        OffsetDateTime reservationEndAt,
        int partySize,
        ReservationStatus status,
        OffsetDateTime checkedInAt,
        Long checkedInByMemberId,
        OffsetDateTime cancelledAt,
        CancellationActor cancellationActor,
        String cancellationReason,
        OffsetDateTime noShowAt,
        Long noShowByMemberId,
        String noShowReason
) {
    public static OwnerReservationResponse from(Reservation reservation) {
        return new OwnerReservationResponse(
                reservation.getId(),
                reservation.getStore().getId(),
                reservation.getMember().getId(),
                reservation.getMember().getName(),
                reservation.getMember().getPhone(),
                reservation.getTableId(),
                reservation.getReservationStartAt(),
                reservation.getReservationEndAt(),
                reservation.getPartySize(),
                reservation.getStatus(),
                reservation.getCheckedInAt(),
                reservation.getCheckedInByMemberId(),
                reservation.getCancelledAt(),
                reservation.getCancellationActor(),
                reservation.getCancellationReason(),
                reservation.getNoShowAt(),
                reservation.getNoShowByMemberId(),
                reservation.getNoShowReason()
        );
    }
}
