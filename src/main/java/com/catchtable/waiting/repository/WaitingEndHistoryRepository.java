package com.catchtable.waiting.repository;

import com.catchtable.waiting.entity.WaitingEndHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaitingEndHistoryRepository extends JpaRepository<WaitingEndHistory, Long> {
    List<WaitingEndHistory> findAllByWaitingIdOrderByIdAsc(Long waitingId);
}
