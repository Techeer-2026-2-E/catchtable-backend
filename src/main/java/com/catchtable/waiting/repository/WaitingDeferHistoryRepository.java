package com.catchtable.waiting.repository;

import com.catchtable.waiting.entity.WaitingDeferHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaitingDeferHistoryRepository extends JpaRepository<WaitingDeferHistory, Long> {
    List<WaitingDeferHistory> findAllByWaitingIdOrderByIdAsc(Long waitingId);
}
