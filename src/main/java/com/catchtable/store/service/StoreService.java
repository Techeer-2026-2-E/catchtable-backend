package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.*;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.BusinessHourRepository;
import com.catchtable.store.repository.MenuRepository;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;
    private final BusinessHourRepository businessHourRepository;
    private final MenuRepository menuRepository;
    private final Clock clock;

    public StoreDetailResponse getStoreDetail(Long storeId)
    {
        Store store=storeRepository.findById(storeId)
                .orElseThrow(()->new BusinessException(ErrorCode.STORE_NOT_FOUND));

        List<BusinessHourResponse> businessHours=
                businessHourRepository.findAllByStoreIdOrderByDayOfWeekAsc(storeId).stream()
                        .map(BusinessHourResponse::from)
                        .toList();
        List<MenuResponse> menus=
                menuRepository.findAllByStoreIdOrderByIdAsc(storeId).stream()
                        .map(MenuResponse::from)
                        .toList();
        return StoreDetailResponse.of(store, businessHours, menus);
    }

    public List<CategoryResponse> getCategories()
    {
        return Arrays.stream(StoreCategory.values())
                .map(CategoryResponse::of)
                .toList();
    }

    public StoreListResponse searchStores(StoreSearchCondition condition)
    {
        return condition.hasFilter()
                ? searchWithFilter(condition)
                : listPage(condition);
    }

    // 필터가 없으면 DB에서 해당 페이지만 읽는다
    private StoreListResponse listPage(StoreSearchCondition condition)
    {
        Pageable pageable = PageRequest.of(condition.page(), condition.size(), Sort.by("id"));
        Slice<Store> slice = (condition.category() == null)
                ? storeRepository.findAllBy(pageable)
                : storeRepository.findAllByCategory(condition.category(), pageable);

        List<StoreSummaryResponse> stores = slice.getContent().stream()
                .map(StoreSummaryResponse::of)
                .toList();
        return new StoreListResponse(stores, condition.page(), condition.size(), slice.hasNext());
    }

    private StoreListResponse searchWithFilter(StoreSearchCondition condition)
    {
        LocalDateTime startAt = condition.hasDateTime() ? condition.date().atTime(condition.time()) : null;
        List<Store> matches = storeRepository.searchFiltered(
                condition.category() == null ? null : condition.category().name(),
                condition.keywordPattern(), condition.partySize(), startAt, LocalDateTime.now(clock),
                condition.size() + 1,
                (long) condition.page() * condition.size());
        boolean hasNext = matches.size() > condition.size();
        List<StoreSummaryResponse> stores = matches.stream().limit(condition.size())
                .map(StoreSummaryResponse::of).toList();
        return new StoreListResponse(stores, condition.page(), condition.size(), hasNext);
    }
}
