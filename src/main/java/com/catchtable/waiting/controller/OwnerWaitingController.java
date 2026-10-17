package com.catchtable.waiting.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.waiting.dto.OwnerWaitingCancelRequest;
import com.catchtable.waiting.dto.OwnerWaitingNoShowRequest;
import com.catchtable.waiting.dto.OwnerWaitingResponse;
import com.catchtable.waiting.dto.StoreWaitingStatusResponse;
import com.catchtable.waiting.dto.WaitingCallResponse;
import com.catchtable.waiting.dto.WaitingPolicyRequest;
import com.catchtable.waiting.dto.WaitingPolicyResponse;
import com.catchtable.waiting.dto.WaitingSeatResponse;
import com.catchtable.waiting.dto.WaitingTerminationResponse;
import com.catchtable.waiting.service.OwnerWaitingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner")
public class OwnerWaitingController {

    private final OwnerWaitingService ownerWaitingService;

    @GetMapping("/stores/{storeId}/waitings")
    public List<OwnerWaitingResponse> getWaitings(@LoginMember Long memberId, @PathVariable Long storeId) {
        return ownerWaitingService.getWaitings(memberId, storeId);
    }

    // EventSource는 헤더를 지정할 수 없어 기존 알림과 같은 임시 회원 식별 방식을 쓴다.
    @GetMapping(value = "/stores/{storeId}/waitings/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam Long memberId, @PathVariable Long storeId) {
        return ownerWaitingService.subscribe(memberId, storeId);
    }

    @DeleteMapping("/waitings/{waitingId}")
    public ResponseEntity<Void> cancelWaiting(@LoginMember Long memberId, @PathVariable Long waitingId,
                                              @Valid @RequestBody OwnerWaitingCancelRequest request) {
        ownerWaitingService.cancelWaiting(memberId, waitingId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/waitings/{waitingId}/no-show")
    public WaitingTerminationResponse markNoShow(@LoginMember Long memberId, @PathVariable Long waitingId,
                                                 @Valid @RequestBody OwnerWaitingNoShowRequest request) {
        return ownerWaitingService.markNoShow(memberId, waitingId, request.reason(), request.detail());
    }

    @PostMapping("/stores/{storeId}/waiting/open")
    public StoreWaitingStatusResponse openWaiting(@LoginMember Long memberId, @PathVariable Long storeId) {
        return ownerWaitingService.openWaiting(memberId, storeId);
    }

    @PostMapping("/stores/{storeId}/waiting/close")
    public StoreWaitingStatusResponse closeWaiting(@LoginMember Long memberId, @PathVariable Long storeId) {
        return ownerWaitingService.closeWaiting(memberId, storeId);
    }

    @GetMapping("/stores/{storeId}/waiting-policy")
    public WaitingPolicyResponse getWaitingPolicy(@LoginMember Long memberId, @PathVariable Long storeId) {
        return ownerWaitingService.getWaitingPolicy(memberId, storeId);
    }

    @PutMapping("/stores/{storeId}/waiting-policy")
    public WaitingPolicyResponse updateWaitingPolicy(@LoginMember Long memberId, @PathVariable Long storeId,
                                                     @Valid @RequestBody WaitingPolicyRequest request) {
        return ownerWaitingService.updateWaitingPolicy(memberId, storeId, request.callLimitMinutes());
    }

    @PostMapping("/waitings/{waitingId}/call")
    public WaitingCallResponse callWaiting(@LoginMember Long memberId, @PathVariable Long waitingId) {
        return ownerWaitingService.callWaiting(memberId, waitingId);
    }

    @PostMapping("/waitings/{waitingId}/seat")
    public WaitingSeatResponse seatWaiting(@LoginMember Long memberId, @PathVariable Long waitingId) {
        return ownerWaitingService.seatWaiting(memberId, waitingId);
    }
}
