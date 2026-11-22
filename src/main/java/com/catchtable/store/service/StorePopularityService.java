package com.catchtable.store.service;

import com.catchtable.store.search.StoreSearchOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 매장 인기 점수 갱신 (최근 30일 예약·웨이팅 입장)
@Service
@RequiredArgsConstructor
public class StorePopularityService {

    static final int WINDOW_DAYS = 30;
    // 가중치
    static final int RESERVATION_WEIGHT = 1;
    static final int WAITING_WEIGHT = 1;

    // 집계 → upsert, 대상 아닌 행 삭제(0점). 점수가 바뀐 매장 id 반환
    private static final String REFRESH_SQL = """
            WITH agg AS (
                SELECT store_id, sum(r) AS reservation_count, sum(w) AS waiting_count
                FROM (
                    SELECT store_id, count(*) AS r, 0 AS w FROM reservation
                    WHERE status IN ('CONFIRMED', 'COMPLETED') AND requested_at >= ?
                    GROUP BY store_id
                    UNION ALL
                    SELECT store_id, 0, count(*) FROM waiting
                    WHERE status = 'SEATED' AND waiting_date >= ?
                    GROUP BY store_id
                ) t
                GROUP BY store_id
            ), scored AS (
                SELECT store_id, (reservation_count * ? + waiting_count * ?)::int AS score,
                       reservation_count::int AS reservation_count, waiting_count::int AS waiting_count
                FROM agg
            ), changed AS (
                SELECT s.store_id FROM scored s
                LEFT JOIN store_popularity p ON p.store_id = s.store_id
                WHERE p.score IS DISTINCT FROM s.score
            ), upserted AS (
                INSERT INTO store_popularity (store_id, score, reservation_count, waiting_count, calculated_at)
                SELECT store_id, score, reservation_count, waiting_count, ? FROM scored
                ON CONFLICT (store_id) DO UPDATE SET score = EXCLUDED.score,
                    reservation_count = EXCLUDED.reservation_count, waiting_count = EXCLUDED.waiting_count,
                    calculated_at = EXCLUDED.calculated_at
            ), deleted AS (
                DELETE FROM store_popularity p
                WHERE NOT EXISTS (SELECT 1 FROM scored s WHERE s.store_id = p.store_id)
                RETURNING p.store_id
            )
            SELECT store_id FROM changed UNION SELECT store_id FROM deleted
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StoreSearchOutbox storeSearchOutbox;
    private final Clock clock;

    // 점수가 바뀐 매장은 검색 색인 Outbox에 기록
    @Transactional
    public int refresh() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> changed = jdbcTemplate.queryForList(REFRESH_SQL, Long.class,
                now.minusDays(WINDOW_DAYS), LocalDate.now(clock).minusDays(WINDOW_DAYS),
                RESERVATION_WEIGHT, WAITING_WEIGHT, now);
        changed.forEach(storeSearchOutbox::record);
        return changed.size();
    }
}
