package com.catchtable.store.repository;

import com.catchtable.store.entity.BusinessHour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

// 소프트 삭제된 row는 엔티티의 @SQLRestriction으로 자동 제외된다
public interface BusinessHourRepository extends JpaRepository<BusinessHour, Long> {

    List<BusinessHour> findAllByStoreIdOrderByDayOfWeekAsc(Long storeId);

    Optional<BusinessHour> findByStoreIdAndDayOfWeek(Long storeId, DayOfWeek dayOfWeek);
}
