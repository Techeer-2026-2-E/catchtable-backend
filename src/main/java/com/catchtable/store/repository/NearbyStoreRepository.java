package com.catchtable.store.repository;

import com.catchtable.store.dto.NearbyStoreResponse;
import com.catchtable.store.entity.StoreCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

// 위경도 사각형(idx_store_location)으로 후보를 줄인 뒤, 실제 거리(하버사인)로 반경 밖을 거른다
@Repository
@RequiredArgsConstructor
public class NearbyStoreRepository {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private static final String FIND_NEARBY_SQL = """
            SELECT id, name, category, address, waiting_available, image_url, distance_m
            FROM (
                SELECT id, name, category, address, waiting_available, image_url,
                       2 *:earthRadius * asin(least(1.0, sqrt(
                           power(sin(radians(latitude - :lat) / 2), 2)
                           + cos(radians(:lat)) * cos(radians(latitude))
                             * power(sin(radians(longitude - :lng) / 2), 2)))) AS distance_m
                FROM store
                WHERE latitude BETWEEN :minLat AND :maxLat
                  AND longitude BETWEEN :minLng AND :maxLng
            ) nearby
            WHERE distance_m <= :radius
            ORDER BY distance_m, id
            LIMIT :limit OFFSET :offset
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<NearbyStoreResponse> findNearby(double lat, double lng, double radiusMeters, int limit, long offset) {
        double angularRadius = radiusMeters / EARTH_RADIUS_METERS;
        double latDelta = Math.toDegrees(angularRadius);
        double cosLat = Math.cos(Math.toRadians(lat));
        // 극 근처에서는 경도 범위를 제한할 수 없다
        double lngDelta = Math.sin(angularRadius) >= cosLat
                ? 180.0
                : Math.toDegrees(Math.asin(Math.sin(angularRadius) / cosLat));

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("earthRadius", EARTH_RADIUS_METERS)
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("minLat", lat - latDelta)
                .addValue("maxLat", lat + latDelta)
                .addValue("minLng", lng - lngDelta)
                .addValue("maxLng", lng + lngDelta)
                .addValue("radius", radiusMeters)
                .addValue("limit", limit)
                .addValue("offset", offset);

        return jdbcTemplate.query(FIND_NEARBY_SQL, params, (rs, rowNum) -> {
            StoreCategory category = StoreCategory.valueOf(rs.getString("category"));
            return new NearbyStoreResponse(
                    rs.getLong("id"),
                    rs.getString("name"),
                    category,
                    category.getDescription(),
                    rs.getString("address"),
                    rs.getBoolean("waiting_available"),
                    rs.getString("image_url"),
                    (int) Math.round(rs.getDouble("distance_m")));
        });
    }
}
