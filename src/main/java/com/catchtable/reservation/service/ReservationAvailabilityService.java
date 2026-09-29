package com.catchtable.reservation.service;

import com.catchtable.reservation.dto.AvailabilityResponse;
import com.catchtable.reservation.dto.AvailableTableCountResponse;
import com.catchtable.store.dto.OpeningWindow;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import com.catchtable.store.service.BusinessHourService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationAvailabilityService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    private final StoreRepository storeRepository;
    private final StoreTableRepository storeTableRepository;
    private final BusinessHourService businessHourService;

    public AvailabilityResponse getAvailability(Long storeId, LocalDate date, int partySize) {
        validatePartySize(partySize);
        Store store = findStore(storeId);
        LocalDateTime now = LocalDateTime.now(STORE_ZONE);
        // 오늘부터 bookingOpenDays일 뒤까지 포함한다. 범위 밖은 빈 목록이 아니라 잘못된 요청이다.
        if (date.isBefore(now.toLocalDate()) || date.isAfter(now.toLocalDate().plusDays(store.getBookingOpenDays()))) {
            throw badRequest("예약 가능한 날짜 범위를 벗어났습니다.");
        }

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<AvailabilityResponse.Slot> slots = new ArrayList<>();
        // 전날 밤 영업이 자정을 넘긴 경우 조회 날짜의 새벽 슬롯도 포함한다.
        List<OpeningWindow> windows = new ArrayList<>(businessHourService.getOpeningWindows(storeId, date.minusDays(1)));
        windows.addAll(businessHourService.getOpeningWindows(storeId, date));

        for (OpeningWindow window : windows) {
            // 시작은 조회 날짜 안에 두되, 이용 종료까지 같은 영업 구간에 있어야 한다.
            LocalDateTime first = roundUpToSlot(max(window.start(), dayStart), store.getReservationSlotMinutes());
            LocalDateTime last = min(window.end(), dayEnd);
            for (LocalDateTime start = first;
                 start.isBefore(last) && !start.plusMinutes(store.getReservationDurationMinutes()).isAfter(window.end());
                 start = start.plusMinutes(store.getReservationSlotMinutes())) {
                if (start.isBefore(now.plusMinutes(store.getBookingDeadlineMinutes()))) {
                    continue;
                }
                OffsetDateTime startAt = start.atZone(STORE_ZONE).toOffsetDateTime();
                long count = storeTableRepository.countAvailable(storeId, partySize, startAt,
                        startAt.plusMinutes(store.getReservationDurationMinutes()));
                slots.add(new AvailabilityResponse.Slot(startAt, count, count > 0));
            }
        }
        List<AvailabilityResponse.Slot> ordered = slots.stream().distinct()
                .sorted(Comparator.comparing(AvailabilityResponse.Slot::reservationStartAt))
                .toList();
        return new AvailabilityResponse(storeId, date, partySize, ordered);
    }

    public AvailableTableCountResponse getAvailableTableCount(Long storeId, OffsetDateTime startAt, int partySize) {
        validatePartySize(partySize);
        Store store = findStore(storeId);
        validateBookable(store, startAt, OffsetDateTime.now());
        long count = storeTableRepository.countAvailable(storeId, partySize, startAt,
                startAt.plusMinutes(store.getReservationDurationMinutes()));
        return new AvailableTableCountResponse(storeId, startAt, partySize, count);
    }

    // 단건 가용 수량 조회와 예약 생성에 같은 시간·영업 규칙을 적용한다.
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

    private Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "매장을 찾을 수 없습니다."));
    }

    private static void validatePartySize(int partySize) {
        if (partySize <= 0) {
            throw badRequest("예약 인원은 1명 이상이어야 합니다.");
        }
    }

    // 슬롯 경계로 올림한다. 30분 간격에서 10:00:01은 10:30으로 넘어간다.
    private static LocalDateTime roundUpToSlot(LocalDateTime time, int slotMinutes) {
        LocalDateTime midnight = time.toLocalDate().atStartOfDay();
        long minutes = Duration.between(midnight, time).toMinutes();
        long rounded = ((minutes + slotMinutes - 1) / slotMinutes) * slotMinutes;
        LocalDateTime candidate = midnight.plusMinutes(rounded);
        return candidate.isBefore(time) ? candidate.plusMinutes(slotMinutes) : candidate;
    }

    private static LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
