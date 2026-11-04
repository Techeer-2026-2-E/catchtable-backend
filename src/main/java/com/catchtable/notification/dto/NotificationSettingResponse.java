package com.catchtable.notification.dto;

import com.catchtable.notification.entity.MemberNotificationSetting;

public record NotificationSettingResponse(
        boolean reservationEnabled,
        boolean waitingEnabled
) {
    public static NotificationSettingResponse from(MemberNotificationSetting setting) {
        return new NotificationSettingResponse(setting.isReservationEnabled(), setting.isWaitingEnabled());
    }
}
