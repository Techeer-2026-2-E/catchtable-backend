package com.catchtable.store.dto;

import java.time.LocalDateTime;

// 실제로 영업 중인 연속 구간 [start, end). 브레이크타임이 있으면 하루에 2개로 나뉜다.
// 시각은 모두 매장 현지 시각(Asia/Seoul)
public record OpeningWindow(LocalDateTime start, LocalDateTime end) {

    public boolean contains(LocalDateTime at) {
        return !at.isBefore(start) && at.isBefore(end);
    }

    public boolean covers(LocalDateTime from, LocalDateTime to) {
        return !from.isBefore(start) && !to.isAfter(end);
    }
}
