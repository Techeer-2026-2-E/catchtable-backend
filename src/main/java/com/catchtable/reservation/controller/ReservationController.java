package com.catchtable.reservation.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/reservations")
@RequiredArgsConstructor
public class ReservationController {

    // 인증 연동 전까지 로컬 목 고객으로 요청을 처리한다. 실제 사용자 ID를 받는 API는 아니다.
    private static final long MOCK_CUSTOMER_ID = 1L;

    private final ReservationService reservationService;

    /** 생성 시 가용 여부를 다시 확인하고 바로 확정(201)한다. 그 사이 테이블이 소진되면 409를 반환한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse createReservation(@Valid @RequestBody CreateReservationRequest request) {
        return reservationService.createReservation(MOCK_CUSTOMER_ID, request);
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
