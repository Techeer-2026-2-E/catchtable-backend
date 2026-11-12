package com.catchtable.store.repository;

import java.time.OffsetDateTime;
import java.util.List;

public interface StoreTableAvailabilityRepository {

    // 입력 슬롯과 같은 순서로 수량을 반환한다. 빈 입력은 SQL 없이 빈 결과를 반환한다.
    List<Long> countAvailableBySlots(Long storeId, int partySize, List<OffsetDateTime> starts,
                                     int durationMinutes, OffsetDateTime now);
}
