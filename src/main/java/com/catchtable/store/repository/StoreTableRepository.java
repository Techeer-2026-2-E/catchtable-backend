package com.catchtable.store.repository;

import com.catchtable.store.entity.StoreTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface StoreTableRepository extends JpaRepository<StoreTable, Long> {

    List<StoreTable> findAllByStoreIdOrderByTableNumberAsc(Long storeId);

    boolean existsByStoreIdAndTableNumber(Long storeId, int tableNumber);

    Optional<StoreTable> findByIdAndStoreId(Long id, Long storeId);

    // 잠긴 테이블은 건너뛰고 가장 작은 적합 테이블을 선점한다. [시작, 종료)라 끝점이 맞닿는 예약은 허용된다.
    @Query(value = """
            SELECT st.*
            FROM store_table st
            WHERE st.store_id = :storeId
              AND st.status = 'ACTIVE'
              AND st.min_capacity <= :partySize
              AND st.capacity >= :partySize
              AND NOT EXISTS (
                  SELECT 1
                  FROM reservation r
                  WHERE r.table_id = st.id
                    AND r.status <> 'CANCELLED'
                    AND tstzrange(r.reservation_start_at, r.reservation_end_at, '[)')
                        && tstzrange(CAST(:reservationStartAt AS TIMESTAMPTZ),
                                     CAST(:reservationEndAt AS TIMESTAMPTZ), '[)')
              )
            ORDER BY st.capacity, st.id
            LIMIT 1
            FOR UPDATE OF st SKIP LOCKED
            """, nativeQuery = true)
    Optional<StoreTable> findAvailableForUpdate(
            @Param("storeId") Long storeId,
            @Param("partySize") int partySize,
            @Param("reservationStartAt") OffsetDateTime reservationStartAt,
            @Param("reservationEndAt") OffsetDateTime reservationEndAt
    );

    // 조회용 수량은 선점하지 않는 순간값이며, 실제 생성 시 위 쿼리로 다시 확인한다.
    @Query(value = """
            SELECT count(*)
            FROM store_table st
            WHERE st.store_id = :storeId
              AND st.status = 'ACTIVE'
              AND st.min_capacity <= :partySize
              AND st.capacity >= :partySize
              AND NOT EXISTS (
                  SELECT 1
                  FROM reservation r
                  WHERE r.table_id = st.id
                    AND r.status <> 'CANCELLED'
                    AND tstzrange(r.reservation_start_at, r.reservation_end_at, '[)')
                        && tstzrange(CAST(:reservationStartAt AS TIMESTAMPTZ),
                                     CAST(:reservationEndAt AS TIMESTAMPTZ), '[)')
              )
            """, nativeQuery = true)
    long countAvailable(
            @Param("storeId") Long storeId,
            @Param("partySize") int partySize,
            @Param("reservationStartAt") OffsetDateTime reservationStartAt,
            @Param("reservationEndAt") OffsetDateTime reservationEndAt
    );
}
