package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourResponse;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.OpeningWindow;
import com.catchtable.store.entity.BusinessHour;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.BusinessHourRepository;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusinessHourService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    private final StoreRepository storeRepository;
    private final BusinessHourRepository businessHourRepository;
    private final ReservationRepository reservationRepository;

    // TODO: 점주 본인 매장인지 확인 (로그인 기능 만든 후 작업)

    public List<BusinessHourResponse> getBusinessHours(Long storeId) {
        if (!storeRepository.existsById(storeId)) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
        return businessHourRepository.findAllByStoreIdOrderByDayOfWeekAsc(storeId).stream()
                .map(BusinessHourResponse::from)
                .toList();
    }

    // 일주일치를 통째로 교체. 이미 있는 요일은 수정, 새 요일은 추가, 빠진 요일은 소프트 삭제(= 휴무)
    @Transactional
    public List<BusinessHourResponse> replaceBusinessHours(Long storeId, BusinessHourUpdateRequest request) {
        // 예약 생성의 공유 잠금과 맞물려, 영업시간 검증 중 새 예약이 생기지 않게 한다.
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        Set<DayOfWeek> requestedDays = EnumSet.noneOf(DayOfWeek.class);
        for (BusinessHourRequest hour : request.businessHours()) {
            if (!requestedDays.add(hour.dayOfWeek())) {
                throw new BusinessException(ErrorCode.DUPLICATE_BUSINESS_DAY);
            }
        }

        Map<DayOfWeek, BusinessHour> existing = new EnumMap<>(DayOfWeek.class);
        businessHourRepository.findAllByStoreIdOrderByDayOfWeekAsc(storeId)
                .forEach(hour -> existing.put(hour.getDayOfWeek(), hour));

        for (BusinessHourRequest hour : request.businessHours()) {
            BusinessHour current = existing.remove(hour.dayOfWeek());
            if (current != null) {
                current.changeHours(hour.openTime(), hour.closingTime(),
                        hour.breakStartTime(), hour.breakEndTime());
            } else {
                businessHourRepository.save(BusinessHour.builder()
                        .store(store)
                        .dayOfWeek(hour.dayOfWeek())
                        .openTime(hour.openTime())
                        .closingTime(hour.closingTime())
                        .breakStartTime(hour.breakStartTime())
                        .breakEndTime(hour.breakEndTime())
                        .build());
            }
        }
        existing.values().forEach(BusinessHour::delete);
        businessHourRepository.flush();

        List<BusinessHour> updatedHours = businessHourRepository.findAllByStoreIdOrderByDayOfWeekAsc(storeId);
        // 저장은 아직 같은 트랜잭션 안이다. 충돌이 있으면 추가·변경·휴무 처리를 모두 롤백한다.
        for (Reservation reservation : reservationRepository.findAllByStore_IdAndStatusAndReservationEndAtAfter(
                storeId, ReservationStatus.CONFIRMED, OffsetDateTime.now())) {
            LocalDateTime start = reservation.getReservationStartAt().atZoneSameInstant(STORE_ZONE).toLocalDateTime();
            LocalDateTime end = reservation.getReservationEndAt().atZoneSameInstant(STORE_ZONE).toLocalDateTime();
            boolean covered = Stream.of(start.toLocalDate().minusDays(1), start.toLocalDate())
                    .flatMap(date -> updatedHours.stream()
                            .filter(hour -> hour.getDayOfWeek() == date.getDayOfWeek())
                            .flatMap(hour -> hour.windowsOn(date).stream()))
                    .anyMatch(window -> window.covers(start, end));
            if (!covered) {
                throw new BusinessException(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);
            }
        }
        return updatedHours.stream()
                .map(BusinessHourResponse::from)
                .toList();
    }

    // ---- 예약(#6·#7)·웨이팅(#12)·검색(#2)에서 호출하는 조회 ----
    // 시각은 모두 매장 현지 시각(Asia/Seoul). 존재하지 않는 매장은 영업시간이 없으므로 항상 "영업 안 함"

    // 영업일 기준 영업 구간. 금요일 18:00~02:00 영업이면 금요일을 넣었을 때 토요일 02:00까지 포함된다
    public List<OpeningWindow> getOpeningWindows(Long storeId, LocalDate businessDate) {
        return businessHourRepository.findByStoreIdAndDayOfWeek(storeId, businessDate.getDayOfWeek())
                .map(hour -> hour.windowsOn(businessDate))
                .orElse(List.of());
    }

    // 그 시각에 영업 중인지 (웨이팅 신청 등). 전날 영업이 자정을 넘겨 이어지는 경우도 포함
    public boolean isOpen(Long storeId, LocalDateTime at) {
        return windowsAround(storeId, at.toLocalDate()).anyMatch(window -> window.contains(at));
    }

    // [from, to) 전체가 하나의 영업 구간 안에 들어가는지 (예약 이용시간 등). 브레이크타임에 걸치면 false
    public boolean isOpenBetween(Long storeId, LocalDateTime from, LocalDateTime to) {
        return windowsAround(storeId, from.toLocalDate()).anyMatch(window -> window.covers(from, to));
    }

    private Stream<OpeningWindow> windowsAround(Long storeId, LocalDate date) {
        return Stream.concat(
                getOpeningWindows(storeId, date.minusDays(1)).stream(),
                getOpeningWindows(storeId, date).stream()
        );
    }
}
