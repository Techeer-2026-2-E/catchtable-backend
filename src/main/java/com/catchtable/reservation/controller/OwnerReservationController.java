package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.OwnerReservationActionRequest;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.OwnerReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/owner")
@RequiredArgsConstructor
public class OwnerReservationController {

    // 인증 연동 전까지 로컬 목 점주로 요청을 처리한다.
    private static final long MOCK_OWNER_ID = 2L;

    private final OwnerReservationService ownerReservationService;

    /** date는 서울 기준 예약 시작일(영업일 아님)이다. status를 생략하면 모든 상태를 조회한다. */
    @GetMapping("/stores/{storeId}/reservations")
    public List<OwnerReservationResponse> getReservations(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) ReservationStatus status
    ) {
        return ownerReservationService.getReservations(MOCK_OWNER_ID, storeId, date, status);
    }

    @GetMapping("/reservations/{reservationId}")
    public OwnerReservationResponse getReservation(@PathVariable Long reservationId) {
        return ownerReservationService.getReservation(MOCK_OWNER_ID, reservationId);
    }

    /** 취소 사유는 필수이며, 입장 확인 전의 확정 예약만 취소할 수 있다. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public OwnerReservationResponse cancelReservation(
            @PathVariable Long reservationId,
            @Valid @RequestBody OwnerReservationActionRequest request
    ) {
        return ownerReservationService.cancelReservation(MOCK_OWNER_ID, reservationId, request.reason());
    }


}
