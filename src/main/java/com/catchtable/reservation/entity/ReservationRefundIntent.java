package com.catchtable.reservation.entity;

public enum ReservationRefundIntent {
    PAYMENT_ONLY, CUSTOMER_CANCEL, OWNER_CANCEL, UNCONFIRMED_REFUND;

    public boolean cancelsReservation() {
        return this == CUSTOMER_CANCEL || this == OWNER_CANCEL;
    }

    public CancellationActor actor() {
        return this == OWNER_CANCEL ? CancellationActor.OWNER : CancellationActor.CUSTOMER;
    }
}
