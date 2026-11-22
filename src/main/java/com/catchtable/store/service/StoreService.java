package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.review.dto.StoreReviewStat;
import com.catchtable.review.repository.ReviewRepository;
import com.catchtable.store.dto.*;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.BusinessHourRepository;
import com.catchtable.store.repository.MenuRepository;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.search.StoreSearchIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;
    private final BusinessHourRepository businessHourRepository;
    private final MenuRepository menuRepository;
    private final StoreOpenStatusService storeOpenStatusService;
    private final ReviewRepository reviewRepository;
    private final Clock clock;
    private final StoreOpenWindowService storeOpenWindowService;
    private final Optional<StoreSearchIndex> storeSearchIndex;   // catchtable.search.engine=opensearch일 때만 사용

    private static final int MAX_KEYWORD_PAGE = 49;

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

    // 날짜 검색은 영업 구간을 계산·기록할 수 있어 쓰기 트랜잭션
    @Transactional
    public StoreListResponse searchStores(StoreSearchCondition condition)
    {
        if (usesOpenSearch(condition)) {
            return searchByKeyword(condition);
        }
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
        return new StoreListResponse(withStats(stores), condition.page(), condition.size(), slice.hasNext());
    }

    private StoreListResponse searchWithFilter(StoreSearchCondition condition)
    {
        String category = condition.category() == null ? null : condition.category().name();
        int limit = condition.size() + 1;
        long offset = (long) condition.page() * condition.size();
        List<Store> matches;
        if (condition.hasDateTime()) {
            storeOpenWindowService.ensureForSearch(condition.date());
            matches = storeRepository.searchFilteredAt(category, condition.keywordPattern(), condition.partySize(),
                    condition.date().atTime(condition.time()), LocalDateTime.now(clock), limit, offset);
        } else {
            matches = storeRepository.searchFiltered(category, condition.keywordPattern(), condition.partySize(),
                    limit, offset);
        }
        boolean hasNext = matches.size() > condition.size();
        List<StoreSummaryResponse> stores = matches.stream().limit(condition.size())
                .map(StoreSummaryResponse::of).toList();
        return new StoreListResponse(withStats(stores), condition.page(), condition.size(), hasNext);
    }

    // 키워드만 있는 검색(카테고리 함께 가능)
    private boolean usesOpenSearch(StoreSearchCondition condition)
    {
        return condition.keyword() != null && condition.partySize() == null && !condition.hasDateTime()
                && storeSearchIndex.filter(StoreSearchIndex::searchEnabled).isPresent();
    }

    // OpenSearch는 id 순서만, 응답은 DB 매장으로. 장애 시 DB 키워드 검색으로 대체
    private StoreListResponse searchByKeyword(StoreSearchCondition condition)
    {
        if (condition.page() > MAX_KEYWORD_PAGE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        List<Long> ids;
        try {
            ids = storeSearchIndex.orElseThrow().searchIds(condition.keyword(), condition.category(),
                    condition.page() * condition.size(), condition.size() + 1);
        } catch (RestClientException e) {
            log.warn("OpenSearch 키워드 검색 실패, DB 검색으로 대체: {}", e.toString());
            return searchWithFilter(condition);
        }
        boolean hasNext = ids.size() > condition.size();
        List<Long> pageIds = ids.stream().limit(condition.size()).toList();
        Map<Long, Store> found = storeRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));
        List<StoreSummaryResponse> stores = pageIds.stream()
                .map(found::get).filter(Objects::nonNull)
                .map(StoreSummaryResponse::of).toList();
        return new StoreListResponse(withStats(stores), condition.page(), condition.size(), hasNext);
    }

    // 한 페이지 매장의 영업 상태·별점을 id 목록으로 한 번씩만 읽어 채운다 (지도 목록 MapStoreEnricher와 같은 규칙)
    private List<StoreSummaryResponse> withStats(List<StoreSummaryResponse> stores)
    {
        if (stores.isEmpty()) {
            return stores;
        }
        List<Long> storeIds = stores.stream().map(StoreSummaryResponse::id).toList();
        Map<Long, OpenStatusInfo> statuses = storeOpenStatusService.getOpenStatuses(storeIds);
        Map<Long, StoreReviewStat> reviews = new HashMap<>();
        reviewRepository.summarizeByStoreIds(storeIds).forEach(stat -> reviews.put(stat.storeId(), stat));

        return stores.stream()
                .map(store -> {
                    StoreReviewStat review = reviews.get(store.id());
                    return store.withOpenStatusAndReview(
                            statuses.get(store.id()),
                            review == null ? null : MapStoreEnricher.roundRating(review.averageRating()),
                            review == null ? 0 : review.reviewCount());
                })
                .toList();
    }
}
