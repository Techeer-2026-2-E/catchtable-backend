package com.catchtable.reservation.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.RefundPreviewResponse;
import com.catchtable.reservation.dto.ReservationHoldResponse;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/reservations")
@RequiredArgsConstructor
public class ReservationController {

    // 인증 연동 전까지 로컬 목 고객으로 요청을 처리한다. 실제 사용자 ID를 받는 API는 아니다.
    private static final long MOCK_CUSTOMER_ID = 1L;

    private final ReservationService reservationService;

    /** 알림 수신 또는 SSE 재접속 후 본인 예약의 최신 상태와 취소 사유를 조회한다. */
    @GetMapping("/{reservationId}")
    public ReservationResponse getReservation(@LoginMember Long memberId, @PathVariable Long reservationId) {
        return reservationService.getReservation(memberId, reservationId);
    }

    @GetMapping("/{reservationId}/refund-preview")
    public RefundPreviewResponse getRefundPreview(@LoginMember Long memberId, @PathVariable Long reservationId) {
        return reservationService.getRefundPreview(memberId, reservationId);
    }

    /** 생성 시 가용 여부를 다시 확인하고 바로 확정(201)한다. 그 사이 테이블이 소진되면 409를 반환한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse createReservation(@Valid @RequestBody CreateReservationRequest request) {
        return reservationService.createReservation(MOCK_CUSTOMER_ID, request);
    }

    /** 결제 대기 예약과 5분 선점을 함께 만든다. */
    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationHoldResponse createHold(
            @LoginMember Long memberId,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        return reservationService.createHold(memberId, request);
    }

    /** 새로고침해도 최초 만료시간을 유지하며, 본인 선점만 조회한다. */
    @GetMapping("/holds/{holdId}")
    public ReservationHoldResponse getHold(@LoginMember Long memberId, @PathVariable Long holdId) {
        return reservationService.getHold(memberId, holdId);
    }

    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<Void> releaseHold(@LoginMember Long memberId, @PathVariable Long holdId) {
        reservationService.releaseHold(memberId, holdId);
        return ResponseEntity.noContent().build();
    }

    /** 예약 시작 전의 본인 확정 예약만 취소할 수 있다. 이미 취소된 예약은 그대로 반환한다. */
    @PostMapping("/{reservationId}/cancel")
    public ReservationResponse cancelReservation(
            @LoginMember Long memberId,
            @PathVariable Long reservationId
    )
    {
        return reservationService.cancelReservation(memberId, reservationId);
    }

}
