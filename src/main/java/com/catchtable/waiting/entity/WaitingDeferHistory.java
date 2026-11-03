package com.catchtable.waiting.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

// 순번 연기 이력
@Entity
@Table(name = "waiting_defer_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitingDeferHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "waiting_id", nullable = false)
    private Waiting waiting;

    // 변경 전 순번
    @Column(name = "before_position", nullable = false)
    private int beforePosition;

    // 변경 후 순번
    @Column(name = "after_position", nullable = false)
    private int afterPosition;

    // 처리 점주
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    static WaitingDeferHistory record(Waiting waiting, int beforePosition, int afterPosition,
                                      Long actorId, OffsetDateTime now) {
        WaitingDeferHistory history = new WaitingDeferHistory();
        history.waiting = waiting;
        history.beforePosition = beforePosition;
        history.afterPosition = afterPosition;
        history.actorId = actorId;
        history.createdAt = now;
        return history;
    }
}
