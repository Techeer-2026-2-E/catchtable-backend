package com.catchtable.waiting.repository;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WaitingRepository extends JpaRepository<Waiting, Long> {

    // 매장 행을 함께 잠그지 않아 신청의 매장 락과 교착되지 않게 한다.
    @Query(value = """
            select w.* from waiting w
            join store s on s.id = w.store_id
            where w.id = :waitingId and s.owner_id = :ownerId
            for update of w
            """, nativeQuery = true)
    Optional<Waiting> findByIdAndOwnerIdForUpdate(@Param("waitingId") Long waitingId,
                                                @Param("ownerId") Long ownerId);

    // 본인 웨이팅 행 잠금
    @Query(value = """
            select * from waiting
            where id = :waitingId and member_id = :memberId
            for update
            """, nativeQuery = true)
    Optional<Waiting> findByIdAndMemberIdForUpdate(@Param("waitingId") Long waitingId,
                                                 @Param("memberId") Long memberId);

    // 호출 기한 지난 웨이팅
    @Query(value = """
            select id from waiting
            where status in ('CALLED', 'CONFIRMED') and call_expires_at < :now
            order by id
            """, nativeQuery = true)
    List<Long> findCallExpiredIds(@Param("now") OffsetDateTime now);

    // 웨이팅 행만 잠금 (매장 락 없음)
    @Query(value = "select * from waiting where id = :waitingId for update", nativeQuery = true)
    Optional<Waiting> findByIdForUpdate(@Param("waitingId") Long waitingId);

    @EntityGraph(attributePaths = {"member"})
    List<Waiting> findAllByStoreIdAndWaitingDateAndStatusInOrderByWaitNumberAsc(
            Long storeId, LocalDate waitingDate, Collection<WaitingStatus> statuses);

    // 고객의 진행 중 웨이팅 조회 (같은 매장·같은 날)
    Optional<Waiting> findFirstByMemberIdAndStoreIdAndWaitingDateAndStatusIn(
            Long memberId, Long storeId, LocalDate waitingDate, Collection<WaitingStatus> statuses);

    // 오늘 마지막 대기번호 (없으면 0)
    @Query("""
            select coalesce(max(w.waitNumber), 0)
            from Waiting w
            where w.store.id = :storeId and w.waitingDate = :waitingDate
            """)
    int findMaxWaitNumber(@Param("storeId") Long storeId, @Param("waitingDate") LocalDate waitingDate);

    // 내 앞 대기 팀 수
    long countByStoreIdAndWaitingDateAndStatusInAndWaitNumberLessThan(
            Long storeId, LocalDate waitingDate, Collection<WaitingStatus> statuses, int waitNumber);

    // 고객 내역 조회용 (매장명 표시를 위해 매장 함께 조회)
    @EntityGraph(attributePaths = {"store"})
    List<Waiting> findAllByMemberIdAndStatusIn(Long memberId, Collection<WaitingStatus> statuses);

    @EntityGraph(attributePaths = {"store"})
    Optional<Waiting> findByIdAndMemberId(Long id, Long memberId);
}
