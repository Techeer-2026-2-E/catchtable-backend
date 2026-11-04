package com.catchtable.store.service;

import com.catchtable.reservation.service.ReservationDepositTermsReader;
import com.catchtable.store.entity.Store;
import org.springframework.stereotype.Component;

// 예약 생성 트랜잭션에서 이미 잠긴 매장의 예약금 설정을 그대로 넘긴다. 추가 조회·외부 호출은 없다.
@Component
public class StoreDepositTermsReader implements ReservationDepositTermsReader {

    @Override
    public Terms read(Store lockedStore) {
        return new Terms(
                lockedStore.isDepositEnabled(),
                lockedStore.getDepositAmount(),
                lockedStore.getRefundPolicy()
        );
    }
}
