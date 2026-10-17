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

    @PostMapping
    public ResponseEntity<WaitingResponse> createWaiting(
            @LoginMember Long memberId,
            @Valid @RequestBody WaitingCreateRequest request
    ) {
        WaitingCreateResult result = waitingService.createWaiting(memberId, request.storeId(), request.partyCount());

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }
}