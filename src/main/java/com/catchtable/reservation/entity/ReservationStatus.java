package com.catchtable.reservation.entity;

/** 응답 상태: CONFIRMED(확정), COMPLETED(방문 후 이용 종료), CANCELLED(취소), NO_SHOW(미방문). */
public enum ReservationStatus {
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
