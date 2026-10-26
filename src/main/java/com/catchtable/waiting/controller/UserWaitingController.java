package com.catchtable.waiting.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.waiting.dto.WaitingPositionResponse;
import com.catchtable.waiting.dto.WaitingRespondResponse;
import com.catchtable.waiting.dto.WaitingTerminationResponse;
import com.catchtable.waiting.service.WaitingQueryService;
import com.catchtable.waiting.service.WaitingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/waitings")
public class UserWaitingController {

    private final WaitingQueryService waitingQueryService;
    private final WaitingService waitingService;

    @GetMapping(value = "/{waitingId}")
    public WaitingPositionResponse getMyWaiting(@LoginMember Long memberId, @PathVariable Long waitingId) {
        return waitingQueryService.getMyWaiting(memberId, waitingId);
    }

    // 호출 응답
    @PostMapping("/{waitingId}/accept")
    public WaitingRespondResponse respondToCall(@LoginMember Long memberId, @PathVariable Long waitingId) {
        return waitingService.respondToCall(memberId, waitingId);
    }

    // 고객 취소
    @DeleteMapping("/{waitingId}")
    public WaitingTerminationResponse cancelWaiting(@LoginMember Long memberId, @PathVariable Long waitingId) {
        return waitingService.cancelWaiting(memberId, waitingId);
    }
}
