package com.catchtable.store.service;

import com.catchtable.store.dto.MapStoreListResponse;
import com.catchtable.store.dto.MapStoreResponse;
import com.catchtable.store.dto.NearbyStoreCondition;
import com.catchtable.store.dto.NearbyStoreListResponse;
import com.catchtable.store.dto.NearbyStoreResponse;
import com.catchtable.store.repository.NearbyStoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NearbyStoreService {

    private final NearbyStoreRepository nearbyStoreRepository;
    private final MapStoreEnricher mapStoreEnricher;

    @Value("${store.nearby.radius-meters:1000}")
    private double radiusMeters;

    public NearbyStoreListResponse getNearbyStores(NearbyStoreCondition condition) {
        int size = condition.size(); 
        long offset = (long) condition.page() * size;

        // 한 건 더 읽어서 다음 페이지가 있는지만 판단한다
        List<NearbyStoreResponse> rows = nearbyStoreRepository.findNearby(
                condition.lat(), condition.lng(), radiusMeters, size + 1, offset);

        boolean hasNext = rows.size() > size;
        List<NearbyStoreResponse> stores = hasNext ? rows.subList(0, size) : rows;
        return new NearbyStoreListResponse(stores, condition.page(), size, hasNext);
    }

    // 지도 화면의 반경 목록. 반경과 페이징 규칙은 주변 매장과 같다
    public MapStoreListResponse getMapStores(NearbyStoreCondition condition) {
        int size = condition.size();
        long offset = (long) condition.page() * size;

        List<MapStoreResponse> rows = nearbyStoreRepository.findMapStores(
                condition.lat(), condition.lng(), radiusMeters, size + 1, offset);

        boolean hasNext = rows.size() > size;
        List<MapStoreResponse> stores = hasNext ? rows.subList(0, size) : rows;
        // 영업 상태·별점은 응답에 담을 페이지 매장에만 일괄 조회로 채운다
        return new MapStoreListResponse(mapStoreEnricher.enrich(stores), condition.page(), size, hasNext);
    }
}
