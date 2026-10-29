package com.catchtable.payment.dto;

import java.time.LocalDate;

// 점주 기간별 매출. 승인 시각이 기간 안인 성공 결제 기준이라 승인액·환불액·순결제액이 같은 결제 집합이다
public record SalesResponse(
        LocalDate from,
        LocalDate to,
        long approvedAmount,
        long refundedAmount,
        long netAmount,
        String notice
) {
    public static final String NOTICE =
            "실제 매출·세금·PG 수수료·점주 정산액이 아닌 테스트 결제 기반 모의 집계입니다.";

    public static SalesResponse of(LocalDate from, LocalDate to, long approvedAmount, long refundedAmount) {
        return new SalesResponse(from, to, approvedAmount, refundedAmount,
                approvedAmount - refundedAmount, NOTICE);
    }
}
