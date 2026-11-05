package com.catchtable.reservation.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.util.List;

@Getter
@RequiredArgsConstructor
public enum RefundPolicy {
    FLEXIBLE("유연", List.of(new Rule(1,100), new Rule(0,50))),
    STANDARD("일반", List.of(new Rule(2,100), new Rule(1,50), new Rule(0,0))),
    STRICT("엄격", List.of(new Rule(3,100), new Rule(2,50), new Rule(1,20), new Rule(0,0)));

    private final String description;
    private final List<Rule> refundRules;

    public int customerRefundRate(long daysBefore) {
        return refundRules.stream().filter(rule -> daysBefore >= rule.minDaysBefore()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("예약일이 지난 고객 취소는 처리할 수 없습니다."))
                .refundRate();
    }

    public record Rule(int minDaysBefore, int refundRate) {}
}
