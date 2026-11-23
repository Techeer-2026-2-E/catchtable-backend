package com.catchtable.waiting;

import com.catchtable.waiting.dto.WaitingCreateRequest;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.service.WaitingIntakeService;

// 실제 신청 API와 같은 진입점(WaitingIntakeService)으로 요청 키 없이 신청한다. 기존 매장은 201·200·409 계약을 따른다.
public final class WaitingApplicant {

    private WaitingApplicant() {
    }

    public static WaitingCreateResult apply(WaitingIntakeService intake, Long memberId, Long storeId, int partyCount) {
        return (WaitingCreateResult) intake.submit(memberId, new WaitingCreateRequest(storeId, partyCount), null);
    }
}
