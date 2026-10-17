package com.catchtable.waiting.controller;

import com.catchtable.global.auth.LoginMember;
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
@RequestMapping("/api/user/waitings")
public class WaitingController {

    private final WaitingService waitingService;

    // 원격 웨이팅 신청
    @PostMapping
    public ResponseEntity<WaitingResponse> createWaiting(
            @LoginMember Long memberId,
            @Valid @RequestBody WaitingCreateRequest request // 매장 ID·인원수
    ) {
        WaitingCreateResult result = waitingService.createWaiting(memberId, request.storeId(), request.partyCount());

        // 신규 201 / 재시도 200
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }
}