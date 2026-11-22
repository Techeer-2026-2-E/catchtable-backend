package com.catchtable.store.search;

import com.catchtable.store.dto.MapStoreResponse;
import com.catchtable.store.entity.StoreCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// 매장·메뉴 DB 최신 값 → 색인 문서 (전체 재색인·변경 동기화 공용)
public class StoreSearchDocuments {

    private static final String SELECT = """
            SELECT s.id, s.name, s.address, s.category, s.owner_id IS NOT NULL AS reservation_enabled,
                   s.waiting_available, s.latitude, s.longitude,
                   ARRAY(SELECT m.name FROM menu m WHERE m.store_id = s.id ORDER BY m.id) AS menus,
                   COALESCE(p.score, 0) AS popularity
            FROM store s
            LEFT JOIN store_popularity p ON p.store_id = s.id
            """;

    private static final RowMapper<Map<String, Object>> TO_DOCUMENT = (rs, rowNum) -> {
        String address = rs.getString("address");
        return StoreSearchIndex.document(
                rs.getLong("id"), rs.getString("name"), address, MapStoreResponse.areaOf(address),
                StoreCategory.valueOf(rs.getString("category")),
                Arrays.asList((String[]) rs.getArray("menus").getArray()),
                rs.getBoolean("reservation_enabled"), rs.getBoolean("waiting_available"),
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
                rs.getInt("popularity"));
    };

    private final JdbcTemplate jdbcTemplate;

    public StoreSearchDocuments(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // id 순 다음 묶음
    public List<Map<String, Object>> findAfter(long lastId, int limit) {
        return jdbcTemplate.query(SELECT + " WHERE s.id > ? ORDER BY s.id LIMIT ?", TO_DOCUMENT, lastId, limit);
    }

    // 지정 매장 (없는 매장은 결과에서 빠짐)
    public List<Map<String, Object>> findByIds(Collection<Long> storeIds) {
        return jdbcTemplate.query(SELECT + " WHERE s.id = ANY(?) ORDER BY s.id", TO_DOCUMENT,
                (Object) storeIds.toArray(Long[]::new));
    }
}
