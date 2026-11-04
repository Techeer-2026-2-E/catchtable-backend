package com.catchtable.notification.entity;

import com.catchtable.notification.dto.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 고객 알림 수신 설정 (저장은 upsert, 시각은 Clock 기준)
@Entity
@Table(name = "member_notification_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberNotificationSetting {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    // 예약 알림 (확정·변경)
    @Column(name = "reservation_enabled", nullable = false)
    private boolean reservationEnabled;

    // 웨이팅 알림 (순번 갱신)
    @Column(name = "waiting_enabled", nullable = false)
    private boolean waitingEnabled;

    // 행 없음 = 기본값
    public static MemberNotificationSetting defaults(Long memberId) {
        MemberNotificationSetting setting = new MemberNotificationSetting();
        setting.memberId = memberId;
        setting.reservationEnabled = true;
        setting.waitingEnabled = true;
        return setting;
    }

    // 수신 여부 (필수·선택 구분은 여기 한 곳)
    public boolean allows(NotificationType type) {
        return switch (type) {
            // 필수 알림
            case WAITING_CALLED, WAITING_EXPIRED, WAITING_CANCELED, RESERVATION_CANCELED, WAITING_CHANGED -> true;
            // 선택 알림
            case RESERVATION_CONFIRMED, RESERVATION_CHANGED -> reservationEnabled;
            case WAITING_POSITION_CHANGED -> waitingEnabled;
        };
    }
}
