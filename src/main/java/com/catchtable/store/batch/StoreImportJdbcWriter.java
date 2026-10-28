package com.catchtable.store.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

//폐업, 좌표 없는 거 빼면 11만 건 디비에 올리기
@Component
@RequiredArgsConstructor
public class StoreImportJdbcWriter {

    // 미입점 매장은 예약 정책 값이 쓰이지 않지만 NOT NULL이라 목 매장과 같은 기본값을 넣는다
    //   - 점주가 입점한 매장(owner_id 있음)은 공공데이터로 덮어쓰지 않는다
    private static final String UPSERT_SQL = """
            INSERT INTO store (external_id, owner_id, name, category, address, phone, latitude, longitude,
                               waiting_available, reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
            VALUES (?, NULL, ?, ?, ?, ?, ?, ?, false, 120, 30, 10)
            ON CONFLICT (external_id) DO UPDATE SET
                name       = EXCLUDED.name,
                category   = EXCLUDED.category,
                address    = EXCLUDED.address,
                phone      = EXCLUDED.phone,
                latitude   = EXCLUDED.latitude,
                longitude  = EXCLUDED.longitude,
                updated_at = now()
            WHERE store.owner_id IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void upsert(List<StoreImportRow> rows) {
        jdbcTemplate.batchUpdate(UPSERT_SQL, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.externalId());
            ps.setString(2, row.name());
            ps.setString(3, row.category().name());
            ps.setString(4, row.address());
            ps.setString(5, row.phone());
            ps.setDouble(6, row.latitude());
            ps.setDouble(7, row.longitude());
        });
    }
}
