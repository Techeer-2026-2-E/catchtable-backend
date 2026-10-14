package com.catchtable.reservation.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 응답 상태: CONFIRMED(확정), COMPLETED(방문 후 이용 종료), CANCELLED(취소), NO_SHOW(미방문). */
public enum ReservationStatus {
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    // 진행 중 상태 묶음 → 고객 내역의 진행 중/종료 구분용
    public static final Set<ReservationStatus> ACTIVE =
            Collections.unmodifiableSet(EnumSet.of(CONFIRMED));
}
