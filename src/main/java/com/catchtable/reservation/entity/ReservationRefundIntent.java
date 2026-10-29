package com.catchtable.reservation.entity;

public enum ReservationRefundIntent {
    PAYMENT_ONLY, CUSTOMER_CANCEL, OWNER_CANCEL;

    public CancellationActor actor() {
        return this == OWNER_CANCEL ? CancellationActor.OWNER : CancellationActor.CUSTOMER;
    }
}
