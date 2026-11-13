package com.catchtable.store.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreTableAvailabilityRepositoryImpl implements StoreTableAvailabilityRepository {

    private final EntityManager entityManager;

    @Override
    public List<Long> countAvailableBySlots(Long storeId, int partySize, List<OffsetDateTime> starts,
                                            int durationMinutes, OffsetDateTime now) {
        if (starts.isEmpty()) {
            return List.of();
        }
        // 시각은 모두 바인딩한다. 슬롯 생성·정렬은 서비스가 담당하며 SQL은 전달된 구간만 센다.
        String values = IntStream.range(0, starts.size())
                .mapToObj(i -> "(" + i + ", CAST(:start" + i + " AS TIMESTAMPTZ), CAST(:end" + i + " AS TIMESTAMPTZ))")
                .collect(Collectors.joining(", "));
        Query query = entityManager.createNativeQuery("""
                SELECT count(st.id)
                FROM (VALUES %s) AS slots(slot_index, start_at, end_at)
                LEFT JOIN store_table st
                  ON st.store_id = :storeId
                 AND st.status = 'ACTIVE'
                 AND st.min_capacity <= :partySize
                 AND st.capacity >= :partySize
                 AND NOT EXISTS (
                     SELECT 1
                     FROM reservation r
                     WHERE r.table_id = st.id
                       AND r.status NOT IN ('CANCELLED', 'EXPIRED')
                       AND (r.status <> 'PENDING_PAYMENT' OR r.hold_expires_at > :now
                            OR r.approval_payment_id IS NOT NULL OR r.approval_quarantined)
                       AND tstzrange(r.reservation_start_at, r.reservation_end_at, '[)')
                           && tstzrange(slots.start_at, slots.end_at, '[)')
                 )
                GROUP BY slots.slot_index
                ORDER BY slots.slot_index
                """.formatted(values));
        query.setParameter("storeId", storeId);
        query.setParameter("partySize", partySize);
        query.setParameter("now", now);
        for (int i = 0; i < starts.size(); i++) {
            query.setParameter("start" + i, starts.get(i));
            query.setParameter("end" + i, starts.get(i).plusMinutes(durationMinutes));
        }
        // countAvailable과 같은 선점 만료·승인 보호 조건이다. 배정 잠금은 생성 경로에서만 잡는다.
        List<?> counts = query.getResultList();
        return counts.stream().map(count -> ((Number) count).longValue()).toList();
    }
}
