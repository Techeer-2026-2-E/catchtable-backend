package com.catchtable.reservation.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RefundPolicy {
    FLEXIBLE("유연"),
    STANDARD("일반"),
    STRICT("엄격");

    private final String description;
}
