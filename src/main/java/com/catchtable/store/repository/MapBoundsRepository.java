package com.catchtable.store.repository;

import com.catchtable.store.dto.MapMarkerResponse;
import com.catchtable.store.dto.MapStoreResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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

    private static final String FIND_MARKERS_SQL = """
            SELECT id, name, latitude, longitude
            FROM store
            WHERE latitude BETWEEN :swLat AND :neLat
              AND longitude BETWEEN :swLng AND :neLng
            ORDER BY id
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
        return jdbcTemplate.query(FIND_MARKERS_SQL, params, (rs, rowNum) -> new MapMarkerResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude")));
    }

    private MapSqlParameterSource boundsParams(double swLat, double swLng, double neLat, double neLng) {
        return new MapSqlParameterSource()
                .addValue("swLat", swLat)
                .addValue("swLng", swLng)
                .addValue("neLat", neLat)
                .addValue("neLng", neLng);
    }
}
