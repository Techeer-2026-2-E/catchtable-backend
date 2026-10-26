package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.AvailabilityResponse;
import com.catchtable.reservation.dto.AvailableTableCountResponse;
import com.catchtable.reservation.service.ReservationAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 가용 수량은 조회 시점 값이며, 실제 테이블 배정은 예약 생성 시 확정된다. */
@RestController
@RequestMapping("/api/user/stores/{storeId}")
@RequiredArgsConstructor
public class StoreAvailabilityController {

    private final ReservationAvailabilityService availabilityService;

    /** 서울 기준 날짜의 예약 시작 시각을 조회한다. 영업시간 밖·예약 마감 전 시각은 제외하고, 잔여 0개인 슬롯은 포함한다. */
    @GetMapping("/availability")
    public AvailabilityResponse getAvailability(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam int partySize
    ) {
        return availabilityService.getAvailability(storeId, date, partySize);
    }

    /** 시작 시각은 오프셋까지 전달한다(예: 2030-01-02T10:00:00+09:00). 테이블 목록이 아닌 잔여 수량을 반환한다. */
    @GetMapping("/tables")
    public AvailableTableCountResponse getAvailableTables(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime reservationStartAt,
            @RequestParam int partySize
    ) {
        return availabilityService.getAvailableTableCount(storeId, reservationStartAt, partySize);
    }
}
