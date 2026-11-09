package com.catchtable.store.service;

import com.catchtable.review.dto.StoreReviewStat;
import com.catchtable.review.repository.ReviewRepository;
import com.catchtable.store.dto.MapStoreResponse;
import com.catchtable.store.dto.OpenStatusInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 지도 목록 한 페이지의 매장들에 영업 상태·별점을 채운다. 매장마다 조회하지 않고 id 목록으로 각각 한 번씩만 읽는다
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MapStoreEnricher {

    private final StoreOpenStatusService storeOpenStatusService;
    private final ReviewRepository reviewRepository;

    public List<MapStoreResponse> enrich(List<MapStoreResponse> stores) {
        if (stores.isEmpty()) {
            return stores;
        }
        List<Long> storeIds = stores.stream().map(MapStoreResponse::id).toList();
        Map<Long, OpenStatusInfo> statuses = storeOpenStatusService.getOpenStatuses(storeIds);

        Map<Long, StoreReviewStat> reviews = new HashMap<>();
        reviewRepository.summarizeByStoreIds(storeIds).forEach(stat -> reviews.put(stat.storeId(), stat));

        return stores.stream()
                .map(store -> {
                    StoreReviewStat review = reviews.get(store.id());
                    return store.withOpenStatusAndReview(
                            statuses.get(store.id()),
                            review == null ? null : roundRating(review.averageRating()),
                            review == null ? 0 : review.reviewCount());
                })
                .toList();
    }

    // 리뷰 목록 API(ReviewService)와 같은 규칙: 소수 첫째 자리까지 반올림
    private static Double roundRating(Double average) {
        if (average == null) {
            return null;
        }
        return Math.round(average * 10) / 10.0;
    }
}
