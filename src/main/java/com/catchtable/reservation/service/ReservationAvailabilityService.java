package com.catchtable.reservation.service;

import com.catchtable.store.entity.Store;
import com.catchtable.store.service.BusinessHourService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationAvailabilityService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    private final BusinessHourService businessHourService;



    public void validateBookable(Store store, OffsetDateTime startAt, OffsetDateTime now) {
        if (!startAt.isAfter(now)) {
            throw badRequest("예약 시작 시각은 현재 시각 이후여야 합니다.");
        }
        LocalDateTime localStart = startAt.atZoneSameInstant(STORE_ZONE).toLocalDateTime();
        LocalDateTime localNow = now.atZoneSameInstant(STORE_ZONE).toLocalDateTime();
        int minuteOfDay = localStart.getHour() * 60 + localStart.getMinute();
        if (localStart.getSecond() != 0 || localStart.getNano() != 0
                || minuteOfDay % store.getReservationSlotMinutes() != 0) {
            throw badRequest("예약 시작 시각이 매장의 예약 시간 간격과 맞지 않습니다.");
        }
        store.validateBookable(localStart, localNow);
        if (!businessHourService.isOpenBetween(store.getId(), localStart,
                localStart.plusMinutes(store.getReservationDurationMinutes()))) {
            throw badRequest("예약 시간이 매장 영업시간을 벗어났습니다.");
        }
    }






    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
