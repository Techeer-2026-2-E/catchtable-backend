package com.catchtable.waiting.controller;

import com.catchtable.waiting.dto.WaitingCreateRequest;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.dto.WaitingResponse;
import com.catchtable.waiting.service.WaitingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/stores/{storeId}/waitings")
public class WaitingController {

    private final WaitingService waitingService;

    // 원격 웨이팅 신청
    // TODO: 인증 적용 후 X-Member-Id → 로그인 사용자로 교체
    @PostMapping
    public ResponseEntity<WaitingResponse> createWaiting(
            @RequestHeader("X-Member-Id") Long memberId,     // 임시 회원 식별
            @PathVariable Long storeId,                      // 매장 ID
            @Valid @RequestBody WaitingCreateRequest request // 인원수
    ) {
        WaitingCreateResult result = waitingService.createWaiting(memberId, storeId, request.partyCount());

        // 신규 201 / 재시도 200
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }
}