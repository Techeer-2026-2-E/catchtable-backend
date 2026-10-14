package com.catchtable.waiting.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

// 웨이팅 상태
public enum WaitingStatus {
    WAITING,    // 대기 중
    CALLED,     // 입장 호출됨
    CONFIRMED,  // 도착 의사 응답
    SEATED,     // 착석 완료
    CANCELED,   // 취소 (고객/점주)
    EXPIRED;    // 순번 만료 (미응답·미착석)

    // 진행 중 상태 묶음 → 중복 신청 체크, 앞 팀 수 계산용
    public static final Set<WaitingStatus> ACTIVE =
            Collections.unmodifiableSet(EnumSet.of(WAITING, CALLED, CONFIRMED));
}