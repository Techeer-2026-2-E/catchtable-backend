package com.catchtable.notification.entity;

import com.catchtable.notification.dto.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

// 회원별 알림함 (설정과 무관하게 저장, 삭제는 soft delete)
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // 웨이팅 id 또는 예약 id (type으로 구분)
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    // 회원별 중복 저장 방지
    @Column(name = "event_key", nullable = false, length = 200, updatable = false)
    private String eventKey;

    // null = 안 읽음
    @Column(name = "read_at")
    private OffsetDateTime readAt;

    // null = 삭제 안 함
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private Notification(Long memberId, NotificationType type, Long targetId,
                         String message, String eventKey, OffsetDateTime now) {
        if (!isInboxType(type)) {
            throw new IllegalArgumentException("알림함 저장 대상이 아님: " + type);
        }
        this.memberId = memberId;
        this.type = type;
        this.targetId = targetId;
        this.message = message;
        this.eventKey = eventKey;
        this.createdAt = now;
    }

    public static Notification create(Long memberId, NotificationType type, Long targetId,
                                      String message, String eventKey, OffsetDateTime now) {
        return new Notification(memberId, type, targetId, message, eventKey, now);
    }

    // 알림함 저장 대상 6종 (점주 화면 갱신·순번 변경은 저장 안 함)
    public static boolean isInboxType(NotificationType type) {
        return switch (type) {
            case WAITING_CALLED, WAITING_EXPIRED, WAITING_CANCELED,
                 RESERVATION_CONFIRMED, RESERVATION_CANCELED, RESERVATION_CHANGED -> true;
            case WAITING_CHANGED, WAITING_POSITION_CHANGED -> false;
        };
    }

    // 본인 알림 여부
    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    // 사용자 삭제 (행은 남김, 두 번 지워도 처음 시각 유지)
    public void delete(OffsetDateTime now) {
        if (deletedAt == null) {
            this.deletedAt = now;
        }
    }
}
