package com.catchtable.store.repository;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    // 리뷰 작성·수정·삭제 때 개수와 별점 합계를 DB에서 직접 더한다.
    // 읽고 더해서 쓰면 동시 요청끼리 값을 덮어쓰므로 쓰지 않는다.
    // 리뷰 변경을 먼저 반영(flush)하고, 영속성 컨텍스트는 비우지 않는다(비우면 리뷰 수정이 사라질 수 있다)
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE store
            SET review_count = review_count + :countDelta,
                rating_sum   = rating_sum + :ratingDelta
            WHERE id = :storeId
            """, nativeQuery = true)
    void applyReviewChange(@Param("storeId") Long storeId,
                           @Param("countDelta") int countDelta,
                           @Param("ratingDelta") long ratingDelta);

    // 필터 없는 목록: DB가 해당 페이지만 읽는다 (Slice는 size+1건을 읽어 hasNext만 계산하고 count 쿼리는 없다)
    Slice<Store> findAllBy(Pageable pageable);

    Slice<Store> findAllByCategory(StoreCategory category, Pageable pageable);

    // 필터 있는 목록: 전부 읽되 id 순으로 정렬해 페이지 사이 순서를 고정한다
    List<Store> findAllByCategory(StoreCategory category, Sort sort);
    // 날짜 없는 필터 목록. 후보 전체를 JVM으로 가져오지 않고 DB에서 거른 뒤 페이징한다.
    // keywordPattern은 이스케이프된 LIKE 패턴이다 (이름 부분 일치, 대소문자 무시).
    // storeIds: 키워드 후보 id 제한("{1,2,3}" 형식, null이면 제한 없음)
    @Query(value = """
            WITH criteria AS (
                SELECT CAST(:category AS TEXT) AS category, CAST(:keywordPattern AS TEXT) AS keyword_pattern,
                       CAST(:partySize AS INTEGER) AS party_size, CAST(:storeIds AS BIGINT[]) AS store_ids
            )
            SELECT s.* FROM store s CROSS JOIN criteria c
            WHERE (c.category IS NULL OR s.category = c.category)
              AND (c.keyword_pattern IS NULL OR s.name ILIKE c.keyword_pattern)
              AND (c.store_ids IS NULL OR s.id = ANY (c.store_ids))
              AND (c.party_size IS NULL OR (s.owner_id IS NOT NULL AND EXISTS (
                  SELECT 1 FROM store_table st
                  WHERE st.store_id = s.id AND st.status = 'ACTIVE'
                    AND st.min_capacity <= c.party_size AND st.capacity >= c.party_size
              )))
            ORDER BY s.id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Store> searchFiltered(@Param("category") String category, @Param("keywordPattern") String keywordPattern,
                               @Param("partySize") Integer partySize, @Param("storeIds") String storeIds,
                               @Param("limit") int limit, @Param("offset") long offset);

    // 날짜·시간 필터 목록. 영업 구간은 미리 계산한 store_open_window(검색 날짜 + 전날의 자정 넘김 영업),
    // 마감·마지막 예약 가능일·테이블은 실시간. 영업 구간 EXISTS를 OR 밖에 둬 해시 세미 조인이 되게 한다
    @Query(value = """
            WITH criteria AS (
                SELECT CAST(:category AS TEXT) AS category, CAST(:keywordPattern AS TEXT) AS keyword_pattern,
                       CAST(:partySize AS INTEGER) AS party_size,
                       CAST(:startAt AS TIMESTAMP) AS start_at, CAST(:now AS TIMESTAMP) AS now_at,
                       CAST(:storeIds AS BIGINT[]) AS store_ids
            )
            SELECT s.* FROM store s CROSS JOIN criteria c
            WHERE s.owner_id IS NOT NULL
              AND (c.category IS NULL OR s.category = c.category)
              AND (c.keyword_pattern IS NULL OR s.name ILIKE c.keyword_pattern)
              AND (c.store_ids IS NULL OR s.id = ANY (c.store_ids))
              AND (c.party_size IS NULL OR EXISTS (
                  SELECT 1 FROM store_table st
                  WHERE st.store_id = s.id AND st.status = 'ACTIVE'
                    AND st.min_capacity <= c.party_size AND st.capacity >= c.party_size
              ))
              AND CAST(c.start_at AS DATE) <= CAST(c.now_at AS DATE) + s.booking_open_days
              AND c.start_at >= c.now_at + s.booking_deadline_minutes * INTERVAL '1 minute'
              AND EXISTS (
                  SELECT 1 FROM store_open_window w
                  WHERE w.store_id = s.id
                    AND w.business_date IN (CAST(c.start_at AS DATE), CAST(c.start_at AS DATE) - 1)
                    AND c.start_at >= w.opens_at
                    AND c.start_at + s.reservation_duration_minutes * INTERVAL '1 minute' <= w.closes_at
                    AND (w.break_starts_at IS NULL
                        OR c.start_at + s.reservation_duration_minutes * INTERVAL '1 minute' <= w.break_starts_at
                        OR c.start_at >= w.break_ends_at)
              )
            ORDER BY s.id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Store> searchFilteredAt(@Param("category") String category, @Param("keywordPattern") String keywordPattern,
                                 @Param("partySize") Integer partySize,
                                 @Param("startAt") LocalDateTime startAt, @Param("now") LocalDateTime now,
                                 @Param("storeIds") String storeIds,
                                 @Param("limit") int limit, @Param("offset") long offset);
}
