package com.catchtable.reservation.service;

import com.catchtable.reservation.entity.RefundPolicy;
import com.catchtable.store.entity.Store;

/**
 * 기능33의 서버 원본을 소비하는 경계. 운영 어댑터는 아직 연결하지 않았다.
 * 이미 FOR SHARE로 잠긴 매장의 원본을 같은 트랜잭션에서 읽고, 설정 쓰기도 이 매장 잠금과 직렬화해야 한다.
 * 외부 통신·상태 변경 없이 읽으며, 확인할 수 없으면 null 또는 예외를 반환한다. 무료로 추정하지 않는다.
 */
@FunctionalInterface
public interface ReservationDepositTermsReader {

    Terms read(Store lockedStore);

    record Terms(boolean depositEnabled, long amount, RefundPolicy refundPolicy) {
        public Terms {
            if (depositEnabled ? amount <= 0 || refundPolicy == null : amount != 0 || refundPolicy != null) {
                throw new IllegalArgumentException("매장의 예약금 원본이 올바르지 않습니다.");
            }
        }
    }
}
