package com.catchtable.notification.entity;

import com.catchtable.notification.dto.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;

// 알림 전송 작업 (아웃박스). 업무 TX에서 저장하고 전송 스레드가 꺼내 보낸다.
@Entity
@Table(name = "notification_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private NotificationJobKind kind;

    // NOTIFY 중복 방지 (업무 이벤트 키:type:memberId)
    @Column(name = "job_key", length = 400, updatable = false)
    private String jobKey;

    // NOTIFY 수신자
    @Column(name = "member_id", updatable = false)
    private Long memberId;

    // REFRESH·QUEUE 대상 매장
    @Column(name = "store_id", updatable = false)
    private Long storeId;

    // QUEUE 대기열 날짜
    @Column(name = "waiting_date", updatable = false)
    private LocalDate waitingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 40, updatable = false)
    private NotificationType eventType;

    // NOTIFY 예약/웨이팅 id
    @Column(name = "target_id", updatable = false)
    private Long targetId;

    // WAITING_CALLED 오래된 호출 거르기
    @Column(name = "call_count", updatable = false)
    private Integer callCount;

    // NOTIFY SSE 본문 JSON (eventId 포함, 다시 보내도 같은 값)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private NotificationJobResult result;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // REFRESH·QUEUE 대상 수 / 실제 전송 수
    private Integer recipients;

    @Column(name = "sent_count")
    private Integer sentCount;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    private NotificationJob(NotificationJobKind kind, String jobKey, Long memberId, Long storeId,
                            LocalDate waitingDate, NotificationType eventType, Long targetId,
                            Integer callCount, String payload, OffsetDateTime now) {
        this.kind = kind;
        this.jobKey = jobKey;
        this.memberId = memberId;
        this.storeId = storeId;
        this.waitingDate = waitingDate;
        this.eventType = eventType;
        this.targetId = targetId;
        this.callCount = callCount;
        this.payload = payload;
        this.status = NotificationJobStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = now;
    }

    // 고객 알림 6종 (알림함 저장 대상과 같음)
    public static NotificationJob notify(String jobKey, Long memberId, NotificationType eventType,
                                         Long targetId, Integer callCount, String payload,
                                         OffsetDateTime now) {
        if (jobKey == null || memberId == null || targetId == null || payload == null) {
            throw new IllegalArgumentException("NOTIFY 필수 값 누락");
        }
        if (!Notification.isInboxType(eventType)) {
            throw new IllegalArgumentException("NOTIFY 대상이 아님: " + eventType);
        }
        return new NotificationJob(NotificationJobKind.NOTIFY, jobKey, memberId, null, null,
                eventType, targetId, callCount, payload, now);
    }

    // 점주 대기 목록 갱신 (수신자는 보낼 때 매장 점주로 찾음)
    public static NotificationJob refresh(Long storeId, OffsetDateTime now) {
        if (storeId == null) {
            throw new IllegalArgumentException("REFRESH 매장 누락");
        }
        return new NotificationJob(NotificationJobKind.REFRESH, null, null, storeId, null,
                null, null, null, null, now);
    }

    // 순번 변경 (대기열은 보낼 때 조회)
    public static NotificationJob queue(Long storeId, LocalDate waitingDate, OffsetDateTime now) {
        if (storeId == null || waitingDate == null) {
            throw new IllegalArgumentException("QUEUE 매장·날짜 누락");
        }
        return new NotificationJob(NotificationJobKind.QUEUE, null, null, storeId, waitingDate,
                null, null, null, null, now);
    }

    // 전송 스레드가 가져감: PENDING이거나, lease가 지난 PROCESSING만
    public void claim(OffsetDateTime now, Duration lease) {
        if (!isClaimable(now)) {
            throw new IllegalStateException("가져갈 수 없는 작업: id=" + id + ", status=" + status);
        }
        this.status = NotificationJobStatus.PROCESSING;
        this.attemptCount++;
        this.startedAt = now;
        this.leaseUntil = now.plus(lease);
    }

    public boolean isClaimable(OffsetDateTime now) {
        return status == NotificationJobStatus.PENDING
                || (status == NotificationJobStatus.PROCESSING && isLeaseExpired(now));
    }

    public boolean isLeaseExpired(OffsetDateTime now) {
        return leaseUntil != null && !leaseUntil.isAfter(now);
    }

    public boolean hasExceededAttempts(int maxAttempts) {
        return attemptCount > maxAttempts;
    }

    // NOTIFY 끝 (SENT, NO_CONNECTION, SETTING_DISABLED, STALE_CALL)
    public void complete(NotificationJobResult result, OffsetDateTime now) {
        requireProcessing();
        finish(NotificationJobStatus.DONE, result, now);
    }

    // REFRESH·QUEUE 끝: 대상 수와 실제 전송 수를 남김
    public void completeFanout(int recipients, int sentCount, OffsetDateTime now) {
        requireProcessing();
        this.recipients = recipients;
        this.sentCount = sentCount;
        finish(NotificationJobStatus.DONE,
                sentCount > 0 ? NotificationJobResult.SENT : NotificationJobResult.NO_CONNECTION, now);
    }

    // 멈춘 REFRESH·QUEUE를 되돌리려는데 같은 매장 PENDING이 이미 있음
    public void merge(OffsetDateTime now) {
        requireProcessing();
        finish(NotificationJobStatus.DONE, NotificationJobResult.MERGED, now);
    }

    // 가져간 횟수 상한 초과
    public void fail(OffsetDateTime now) {
        requireProcessing();
        finish(NotificationJobStatus.FAILED, NotificationJobResult.ATTEMPTS_EXCEEDED, now);
    }

    private void requireProcessing() {
        if (status != NotificationJobStatus.PROCESSING) {
            throw new IllegalStateException("처리 중이 아닌 작업: id=" + id + ", status=" + status);
        }
    }

    private void finish(NotificationJobStatus status, NotificationJobResult result, OffsetDateTime now) {
        this.status = status;
        this.result = result;
        this.finishedAt = now;
        this.leaseUntil = null;
    }
}
