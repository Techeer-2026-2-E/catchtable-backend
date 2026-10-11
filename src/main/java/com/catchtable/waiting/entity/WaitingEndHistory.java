package com.catchtable.waiting.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "waiting_end_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitingEndHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "waiting_id", nullable = false)
    private Waiting waiting;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 20)
    private WaitingStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaitingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_reason", length = 30)
    private WaitingEndReason previousReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WaitingEndReason reason;

    @Column(name = "previous_detail", length = 300)
    private String previousDetail;

    @Column(nullable = false, length = 300)
    private String detail;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    static WaitingEndHistory record(Waiting waiting, WaitingStatus status, WaitingEndReason reason,
                                    String detail, Long actorId, OffsetDateTime now) {
        WaitingEndHistory history = new WaitingEndHistory();
        history.waiting = waiting;
        history.previousStatus = waiting.getStatus();
        history.status = status;
        history.previousReason = waiting.getEndReason();
        history.reason = reason;
        history.previousDetail = waiting.getEndDetail();
        history.detail = detail;
        history.actorId = actorId;
        history.createdAt = now;
        return history;
    }
}
