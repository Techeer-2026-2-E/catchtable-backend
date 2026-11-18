package com.catchtable.store.repository;

import com.catchtable.store.dto.MapMarkerResponse;
import com.catchtable.store.dto.MapStoreResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

// 화면 사각형 안의 매장 조회. 사각형은 위경도 BETWEEN(idx_store_location)으로 거르고,
// 목록은 내 위치에서의 거리(하버사인)로 정렬한다
@Repository
@RequiredArgsConstructor
public class MapBoundsRepository {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private static final String FIND_STORES_SQL = """
            SELECT id, name, category, address, latitude, longitude, waiting_available, image_url,
                   owner_id IS NOT NULL AS reservation_enabled,
                   2 *:earthRadius * asin(least(1.0, sqrt(
                       power(sin(radians(latitude - :lat) / 2), 2)
                       + cos(radians(:lat)) * cos(radians(latitude))
                         * power(sin(radians(longitude - :lng) / 2), 2)))) AS distance_m
            FROM store
            WHERE latitude BETWEEN :swLat AND :neLat
              AND longitude BETWEEN :swLng AND :neLng
            ORDER BY distance_m, id
            LIMIT :limit OFFSET :offset
            """;

    // 화면 안 매장을 별점 평균 높은 순으로. 평균이 같으면 리뷰가 많은 순, 리뷰가 없는 매장은 맨 뒤.
    // 별점 컬럼에는 인덱스가 없어 화면 안 매장을 모두 읽고 상위 N개를 고른다
    private static final String FIND_MARKERS_SQL = """
            SELECT id, name, latitude, longitude, review_count,
                   round(rating_sum::numeric / NULLIF(review_count, 0), 1) AS rating_average
            FROM store
            WHERE latitude BETWEEN :swLat AND :neLat
              AND longitude BETWEEN :swLng AND :neLng
            ORDER BY rating_sum::numeric / NULLIF(review_count, 0) DESC NULLS LAST,
                     review_count DESC,
                     id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<MapStoreResponse> findStores(double swLat, double swLng, double neLat, double neLng,
                                             double lat, double lng, int limit, long offset) {
        MapSqlParameterSource params = boundsParams(swLat, swLng, neLat, neLng)
                .addValue("earthRadius", EARTH_RADIUS_METERS)
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbcTemplate.query(FIND_STORES_SQL, params, new MapStoreRowMapper());
    }

    public List<MapMarkerResponse> findMarkers(double swLat, double swLng, double neLat, double neLng, int limit) {
        MapSqlParameterSource params = boundsParams(swLat, swLng, neLat, neLng)
                .addValue("limit", limit);
        return jdbcTemplate.query(FIND_MARKERS_SQL, params, (rs, rowNum) -> {
            BigDecimal ratingAverage = rs.getBigDecimal("rating_average");   // 리뷰 없으면 null
            return new MapMarkerResponse(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude"),
                    ratingAverage == null ? null : ratingAverage.doubleValue(),
                    rs.getInt("review_count"),
                    rowNum + 1);   // SQL이 정렬한 순서가 곧 순위
        });
    }

    private MapSqlParameterSource boundsParams(double swLat, double swLng, double neLat, double neLng) {
        return new MapSqlParameterSource()
                .addValue("swLat", swLat)
                .addValue("swLng", swLng)
                .addValue("neLat", neLat)
                .addValue("neLng", neLng);
    }
}
