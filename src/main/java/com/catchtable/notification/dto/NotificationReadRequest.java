package com.catchtable.notification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 화면에 보인 가장 최신 알림 id
public record NotificationReadRequest(
        @NotNull @Positive Long lastId
) {
}
