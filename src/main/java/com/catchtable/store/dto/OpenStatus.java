package com.catchtable.store.dto;

// 지금 시각 기준 매장의 영업 상태. 영업시간 정보가 없는 매장은 상태 자체를 주지 않는다(null)
public enum OpenStatus {
    OPEN,           // 영업 중
    BREAK,          // 브레이크타임
    BEFORE_OPEN,    // 오늘 영업 전
    CLOSED          // 영업 종료 또는 휴무
}
