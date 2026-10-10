package com.catchtable.waiting.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.waiting.dto.WaitingPositionResponse;
import com.catchtable.waiting.service.WaitingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/waitings")
public class UserWaitingController {

    private final WaitingQueryService waitingQueryService;

    @GetMapping(value = "/{waitingId}")
    public WaitingPositionResponse getMyWaiting(@LoginMember Long memberId, @PathVariable Long waitingId) {
        return waitingQueryService.getMyWaiting(memberId, waitingId);
    }
}
