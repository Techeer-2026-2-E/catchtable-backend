package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
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

    private final StoreRepository storeRepository;
    private final BusinessHourRepository businessHourRepository;

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
        Store store = storeRepository.findById(storeId)
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

        // TODO: 영업시간 축소·휴무 전환 시 영향받는 미래 예약 처리 (예약 엔티티 생긴 뒤)

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

        return businessHourRepository.findAllByStoreIdOrderByDayOfWeekAsc(storeId).stream()
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
