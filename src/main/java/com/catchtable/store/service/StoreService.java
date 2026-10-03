package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.*;
import com.catchtable.store.entity.BusinessHour;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.store.repository.BusinessHourRepository;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;
    private final BusinessHourRepository businessHourRepository;
    private final StoreTableRepository storeTableRepository;
    private final Clock clock;

    public StoreDetailResponse getStoreDetail(Long storeId)
    {
        Store store=storeRepository.findById(storeId)
                .orElseThrow(()->new BusinessException(ErrorCode.STORE_NOT_FOUND));

        List<BusinessHourResponse> businessHours=
                businessHourRepository.findAllByStoreIdOrderByDayOfWeekAsc(storeId).stream()
                        .map(BusinessHourResponse::from)
                        .toList();
        return StoreDetailResponse.of(store, businessHours);
    }

    public List<CategoryResponse> getCategories()
    {
        return Arrays.stream(StoreCategory.values())
                .map(CategoryResponse::of)
                .toList();
    }

    public List<StoreSummaryResponse> searchStores(StoreSearchCondition condition)
    {
        List<Store> stores = (condition.category() == null)
                ? storeRepository.findAll()
                : storeRepository.findAllByCategory(condition.category());

        if (condition.partySize() != null) {
            stores = filterByPartySize(stores, condition.partySize());
        }
        if (condition.hasDateTime()) {
            stores = filterByDateTime(stores, condition.date().atTime(condition.time()));
        }
        return stores.stream()
                .map(StoreSummaryResponse::of)
                .toList();
    }

    // 이 인원을 앉힐 수 있는 운영 중 테이블이 하나라도 있는 매장만 남긴다
    private List<Store> filterByPartySize(List<Store> stores, int partySize)
    {
        Set<Long> seatableStoreIds = storeTableRepository
                .findAllByStoreIdInAndStatus(idsOf(stores), TableStatus.ACTIVE).stream()
                .filter(table -> table.canSeat(partySize))
                .map(table -> table.getStore().getId())
                .collect(Collectors.toSet());

        return stores.stream()
                .filter(store -> seatableStoreIds.contains(store.getId()))
                .toList();
    }


    private List<Store> filterByDateTime(List<Store> stores, LocalDateTime startAt)
    {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Store> bookable = stores.stream()
                .filter(store -> store.isWithinBookingWindow(startAt, now))
                .toList();

        // 자정 넘는 영업 때문에 전날 요일도 함께 조회
        LocalDate date = startAt.toLocalDate();
        Map<Long, List<BusinessHour>> hoursByStore = businessHourRepository
                .findAllByStoreIdInAndDayOfWeekIn(idsOf(bookable),
                        List.of(date.getDayOfWeek(), date.minusDays(1).getDayOfWeek())).stream()
                .collect(Collectors.groupingBy(hour -> hour.getStore().getId()));

        return bookable.stream()
                .filter(store -> isOpen(hoursByStore.getOrDefault(store.getId(), List.of()),
                        startAt, startAt.plusMinutes(store.getReservationDurationMinutes())))
                .toList();
    }

    private boolean isOpen(List<BusinessHour> hours, LocalDateTime startAt, LocalDateTime endAt)
    {
        LocalDate date = startAt.toLocalDate();
        return hours.stream().anyMatch(hour -> {
            LocalDate businessDate = (hour.getDayOfWeek() == date.getDayOfWeek()) ? date : date.minusDays(1);
            return hour.windowsOn(businessDate).stream()
                    .anyMatch(window -> window.covers(startAt, endAt));
        });
    }

    private List<Long> idsOf(List<Store> stores)
    {
        return stores.stream().map(Store::getId).toList();
    }
}
