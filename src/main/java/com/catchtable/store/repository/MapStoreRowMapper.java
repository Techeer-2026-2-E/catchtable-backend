package com.catchtable.store.repository;

import com.catchtable.store.dto.MapStoreResponse;
import com.catchtable.store.entity.StoreCategory;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

// 반경·사각형 지도 목록 쿼리가 같은 컬럼으로 응답을 만든다
// (id, name, category, address, latitude, longitude, waiting_available, image_url, reservation_enabled, distance_m)
class MapStoreRowMapper implements RowMapper<MapStoreResponse> {

    @Override
    public MapStoreResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        StoreCategory category = StoreCategory.valueOf(rs.getString("category"));
        return new MapStoreResponse(
                rs.getLong("id"),
                rs.getString("name"),
                category.getDescription(),
                rs.getString("image_url"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                MapStoreResponse.areaOf(rs.getString("address")),
                (int) Math.round(rs.getDouble("distance_m")),
                rs.getBoolean("reservation_enabled"),
                rs.getBoolean("waiting_available"),
                null, null, null, 0);   // 영업 상태·별점은 MapStoreEnricher가 페이지 매장에 한꺼번에 채운다
    }
}
