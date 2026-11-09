package com.catchtable.store.dto;

import java.time.LocalDateTime;

// 영업 상태와 다음에 상태가 바뀌는 시각. 시각은 매장 현지 시각(Asia/Seoul)이다.
// 다음 영업을 7일 안에서 찾지 못한 휴무 상태는 nextChangeAt이 null이다
public record OpenStatusInfo(OpenStatus status, LocalDateTime nextChangeAt) {
}
