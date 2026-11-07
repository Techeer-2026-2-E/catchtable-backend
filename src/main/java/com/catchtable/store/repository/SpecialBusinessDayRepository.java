package com.catchtable.store.repository;

import com.catchtable.store.entity.SpecialBusinessDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 소프트 삭제된 row는 엔티티의 @SQLRestriction으로 자동 제외된다
public interface SpecialBusinessDayRepository extends JpaRepository<SpecialBusinessDay, Long> {

    Optional<SpecialBusinessDay> findByStoreIdAndBusinessDate(Long storeId, LocalDate businessDate);

    List<SpecialBusinessDay> findAllByStoreIdOrderByBusinessDateAsc(Long storeId);
}
