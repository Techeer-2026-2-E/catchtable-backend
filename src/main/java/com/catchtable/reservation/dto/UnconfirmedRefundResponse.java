package com.catchtable.reservation.dto;

import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.reservation.entity.ReservationRefundRequest;
import java.time.OffsetDateTime;

public record UnconfirmedRefundResponse(Long requestId, Long paymentId, Long reservationId, String currency,
        long refundAmount, PaymentRefundStatus refundStatus, OffsetDateTime requestedAt, OffsetDateTime completedAt) {
    public static UnconfirmedRefundResponse from(ReservationRefundRequest request) {
        return new UnconfirmedRefundResponse(request.getId(), request.getPayment().getId(),
                request.getReservation().getId(), "KRW", request.getRefundAmount(), request.getStatus(),
                request.getRequestedAt(), request.getCompletedAt());
    }
}
