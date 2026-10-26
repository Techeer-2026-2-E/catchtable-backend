package com.catchtable.reservation.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** PENDING_PAYMENT는 결제 대기 선점, EXPIRED는 선점 만료다. */
public enum ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    EXPIRED;

    // 진행 중 상태 묶음 → 고객 내역의 진행 중/종료 구분용
    public static final Set<ReservationStatus> ACTIVE =
            Collections.unmodifiableSet(EnumSet.of(PENDING_PAYMENT, CONFIRMED));
}
