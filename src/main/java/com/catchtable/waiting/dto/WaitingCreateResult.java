package com.catchtable.waiting.dto;

// 신청 결과 (서비스 → 컨트롤러)
// created: 신규 등록 true / 재시도로 기존 반환 false → 201·200 구분용
public record WaitingCreateResult(WaitingResponse response, boolean created) {
}