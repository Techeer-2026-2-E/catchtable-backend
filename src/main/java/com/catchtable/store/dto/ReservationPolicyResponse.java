package com.catchtable.store.dto;

import com.catchtable.store.entity.Store;

public record ReservationPolicyResponse(
        int reservationDurationMinutes,
        int reservationSlotMinutes,
        int arrivalGraceMinutes,
        int bookingOpenDays,
        int bookingDeadlineMinutes
) {
    public static ReservationPolicyResponse from(Store store)
    {
        return new ReservationPolicyResponse(
                store.getReservationDurationMinutes(),
                store.getReservationSlotMinutes(),
                store.getArrivalGraceMinutes(),
                store.getBookingOpenDays(),
                store.getBookingDeadlineMinutes()
        );
    }
}
