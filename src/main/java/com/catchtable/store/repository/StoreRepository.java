package com.catchtable.store.repository;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store,Long> {

    // 예약 생성끼리는 병행하되, 검증부터 저장까지 매장 설정이 바뀌지 않도록 보호한다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForShare(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Long id);

    // 필터 없는 목록: DB가 해당 페이지만 읽는다 (Slice는 size+1건을 읽어 hasNext만 계산하고 count 쿼리는 없다)
    Slice<Store> findAllBy(Pageable pageable);

    Slice<Store> findAllByCategory(StoreCategory category, Pageable pageable);

    // 필터 있는 목록: 전부 읽되 id 순으로 정렬해 페이지 사이 순서를 고정한다
    List<Store> findAllByCategory(StoreCategory category, Sort sort);
    // 후보 전체를 JVM으로 가져오지 않는다. 영업 구간·예약 창을 먼저 거른 뒤 DB에서 페이징한다.
    // keywordPattern은 이스케이프된 LIKE 패턴이다 (이름 부분 일치, 대소문자 무시).
    @Query(value = """
            WITH criteria AS (
                SELECT CAST(:category AS TEXT) AS category, CAST(:keywordPattern AS TEXT) AS keyword_pattern,
                       CAST(:partySize AS INTEGER) AS party_size,
                       CAST(:startAt AS TIMESTAMP) AS start_at, CAST(:now AS TIMESTAMP) AS now_at
            )
            SELECT s.* FROM store s CROSS JOIN criteria c
            WHERE (c.category IS NULL OR s.category = c.category)
              AND (c.keyword_pattern IS NULL OR s.name ILIKE c.keyword_pattern)
              AND (c.party_size IS NULL OR (s.owner_id IS NOT NULL AND EXISTS (
                  SELECT 1 FROM store_table st
                  WHERE st.store_id = s.id AND st.status = 'ACTIVE'
                    AND st.min_capacity <= c.party_size AND st.capacity >= c.party_size
              )))
              AND (c.start_at IS NULL OR (s.owner_id IS NOT NULL
                  AND CAST(c.start_at AS DATE) <= CAST(c.now_at AS DATE) + s.booking_open_days
                  AND c.start_at >= c.now_at + s.booking_deadline_minutes * INTERVAL '1 minute'
                  AND EXISTS (
                      SELECT 1 FROM (
                          VALUES (CAST(c.start_at AS DATE)), (CAST(c.start_at AS DATE) - 1)
                      ) d(business_date)
                      -- 특정일 설정이 있으면 요일별 설정보다 우선한다. 특정일 휴무는 행이 나오지 않는다.
                      CROSS JOIN LATERAL (
                          SELECT sbd.open_time, sbd.closing_time, sbd.break_start_time, sbd.break_end_time
                          FROM special_business_day sbd
                          WHERE sbd.store_id = s.id AND sbd.deleted_at IS NULL
                            AND sbd.business_date = d.business_date AND sbd.closed = FALSE
                          UNION ALL
                          SELECT bh.open_time, bh.closing_time, bh.break_start_time, bh.break_end_time
                          FROM business_hour bh
                          WHERE bh.store_id = s.id AND bh.deleted_at IS NULL
                            AND bh.day_of_week = EXTRACT(ISODOW FROM d.business_date)
                            AND NOT EXISTS (
                                SELECT 1 FROM special_business_day sbd
                                WHERE sbd.store_id = s.id AND sbd.deleted_at IS NULL
                                  AND sbd.business_date = d.business_date
                            )
                      ) h
                      CROSS JOIN LATERAL (
                          SELECT d.business_date + h.open_time AS opens_at,
                                 d.business_date + h.closing_time
                                     + CASE WHEN h.closing_time <= h.open_time
                                            THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END AS closes_at,
                                 d.business_date + h.break_start_time
                                     + CASE WHEN h.break_start_time < h.open_time
                                            THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END AS break_starts_at,
                                 d.business_date + h.break_end_time
                                     + CASE WHEN h.break_end_time < h.open_time
                                            THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END AS break_ends_at
                      ) w
                      WHERE c.start_at >= w.opens_at
                        AND c.start_at + s.reservation_duration_minutes * INTERVAL '1 minute' <= w.closes_at
                        AND (h.break_start_time IS NULL
                            OR c.start_at + s.reservation_duration_minutes * INTERVAL '1 minute' <= w.break_starts_at
                            OR c.start_at >= w.break_ends_at)
                  )
              ))
            ORDER BY s.id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Store> searchFiltered(@Param("category") String category, @Param("keywordPattern") String keywordPattern,
                               @Param("partySize") Integer partySize,
                               @Param("startAt") LocalDateTime startAt, @Param("now") LocalDateTime now,
                               @Param("limit") int limit, @Param("offset") long offset);
}
