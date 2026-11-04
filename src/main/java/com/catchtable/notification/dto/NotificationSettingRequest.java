package com.catchtable.notification.dto;

import jakarta.validation.constraints.NotNull;

public record NotificationSettingRequest(
        @NotNull Boolean reservationEnabled,
        @NotNull Boolean waitingEnabled
) {
}
