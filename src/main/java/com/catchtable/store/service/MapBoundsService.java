package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.MapBoundsCondition;
import com.catchtable.store.dto.MapBoundsListCondition;
import com.catchtable.store.dto.MapMarkerListResponse;
import com.catchtable.store.dto.MapMarkerResponse;
import com.catchtable.store.dto.MapStoreListResponse;
import com.catchtable.store.dto.MapStoreResponse;
import com.catchtable.store.repository.MapBoundsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MapBoundsService {

    private static final double METERS_PER_DEGREE = 111_195.0;   // 지구 반지름 6,371km 기준 위도 1도

    private final MapBoundsRepository mapBoundsRepository;
    private final MapStoreEnricher mapStoreEnricher;

    // 너무 넓은 범위 요청을 막는 상한이다. 화면을 보고 조정할 수 있도록 설정값으로 둔다
    @Value("${store.map.max-side-km:5}")
    private double maxSideKm;

    // 마커 한 번에 내려줄 최대 개수. 넘으면 truncated로 알린다
    @Value("${store.map.marker-limit:1000}")
    private int markerLimit;

    public MapStoreListResponse getStores(MapBoundsListCondition condition) {
        validateSize(condition.swLat(), condition.swLng(), condition.neLat(), condition.neLng());

        int size = condition.size();
        long offset = (long) condition.page() * size;

        // 한 건 더 읽어서 다음 페이지가 있는지만 판단한다
        List<MapStoreResponse> rows = mapBoundsRepository.findStores(
                condition.swLat(), condition.swLng(), condition.neLat(), condition.neLng(),
                condition.lat(), condition.lng(), size + 1, offset);

        boolean hasNext = rows.size() > size;
        List<MapStoreResponse> stores = hasNext ? rows.subList(0, size) : rows;
        // 영업 상태·별점은 응답에 담을 페이지 매장에만 일괄 조회로 채운다
        return new MapStoreListResponse(mapStoreEnricher.enrich(stores), condition.page(), size, hasNext);
    }

    public MapMarkerListResponse getMarkers(MapBoundsCondition condition) {
        validateSize(condition.swLat(), condition.swLng(), condition.neLat(), condition.neLng());

        List<MapMarkerResponse> rows = mapBoundsRepository.findMarkers(
                condition.swLat(), condition.swLng(), condition.neLat(), condition.neLng(), markerLimit + 1);

        boolean truncated = rows.size() > markerLimit;
        List<MapMarkerResponse> stores = truncated ? rows.subList(0, markerLimit) : rows;
        return new MapMarkerListResponse(stores, truncated);
    }

    // 가로·세로 중 긴 쪽이 상한을 넘으면 거절한다. 경도 1도의 길이는 위도가 높을수록 짧아진다
    private void validateSize(double swLat, double swLng, double neLat, double neLng) {
        double heightMeters = (neLat - swLat) * METERS_PER_DEGREE;
        double centerLat = (swLat + neLat) / 2;
        double widthMeters = (neLng - swLng) * METERS_PER_DEGREE * Math.cos(Math.toRadians(centerLat));

        double maxMeters = maxSideKm * 1000;
        if (heightMeters > maxMeters || widthMeters > maxMeters) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
