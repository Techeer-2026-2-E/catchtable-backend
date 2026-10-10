package com.catchtable.waiting.repository;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WaitingRepository extends JpaRepository<Waiting, Long> {

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
