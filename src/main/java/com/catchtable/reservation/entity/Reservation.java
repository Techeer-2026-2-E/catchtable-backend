package com.catchtable.reservation.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.member.entity.Member;
import com.catchtable.store.entity.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 상태 변경 요청이 재전송되면 최초 처리 시각과 사유를 유지한다. */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    @Column(name = "reservation_start_at", nullable = false)
    private OffsetDateTime reservationStartAt;

    @Column(name = "reservation_end_at", nullable = false)
    private OffsetDateTime reservationEndAt;

    @Column(name = "party_size", nullable = false)
    private int partySize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "confirmed_at", nullable = false)
    private OffsetDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_actor", length = 20)
    private CancellationActor cancellationActor;

    @Column(name = "checked_in_at")
    private OffsetDateTime checkedInAt;

    @Column(name = "checked_in_by_member_id")
    private Long checkedInByMemberId;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "no_show_at")
    private OffsetDateTime noShowAt;

    @Column(name = "no_show_reason", length = 500)
    private String noShowReason;

    @Column(name = "no_show_by_member_id")
    private Long noShowByMemberId;

    private Reservation(
            Member member,
            Store store,
            Long tableId,
            OffsetDateTime reservationStartAt,
            OffsetDateTime reservationEndAt,
            int partySize,
            OffsetDateTime confirmedAt
    ) {
        this.member = Objects.requireNonNull(member);
        this.store = Objects.requireNonNull(store);
        this.tableId = Objects.requireNonNull(tableId);
        this.reservationStartAt = Objects.requireNonNull(reservationStartAt);
        this.reservationEndAt = Objects.requireNonNull(reservationEndAt);
        this.confirmedAt = Objects.requireNonNull(confirmedAt);

        if (tableId <= 0) {
            throw new IllegalArgumentException("테이블 식별자는 1 이상이어야 합니다.");
        }
        if (partySize <= 0) {
            throw new IllegalArgumentException("예약 인원은 1명 이상이어야 합니다.");
        }
        if (!reservationStartAt.isBefore(reservationEndAt)) {
            throw new IllegalArgumentException("예약 시작 시각은 종료 시각보다 빨라야 합니다.");
        }

        this.partySize = partySize;
        // 승인 대기 단계가 없어 생성 즉시 확정한다.
        this.status = ReservationStatus.CONFIRMED;
        this.requestedAt = confirmedAt;
    }

    public static Reservation confirmed(
            Member member,
            Store store,
            Long tableId,
            OffsetDateTime reservationStartAt,
            OffsetDateTime reservationEndAt,
            int partySize,
            OffsetDateTime confirmedAt
    ) {
        return new Reservation(
                member,
                store,
                tableId,
                reservationStartAt,
                reservationEndAt,
                partySize,
                confirmedAt
        );
    }

    // 점주는 현재 구현상 시작 시각이 지나도 입장 확인 전이면 취소할 수 있다.
    public void cancelByOwner(OffsetDateTime cancelledAt, String reason) {
        Objects.requireNonNull(cancelledAt);

        if (status == ReservationStatus.CANCELLED) {
            return;
        }
        if (status != ReservationStatus.CONFIRMED || checkedInAt != null) {
            throw new IllegalStateException("취소할 수 없는 예약입니다.");
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        this.cancellationActor = CancellationActor.OWNER;
        this.cancellationReason = reason;
    }

    public void confirmVisit(OffsetDateTime checkedInAt, Long ownerId) {
        Objects.requireNonNull(checkedInAt);
        Objects.requireNonNull(ownerId);

        if (this.checkedInAt != null) {
            return;
        }
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("방문 확인을 할 수 없는 예약입니다.");
        }

        this.checkedInAt = checkedInAt;
        this.checkedInByMemberId = ownerId;
    }

    public void markNoShow(OffsetDateTime noShowAt, Long ownerId, int arrivalGraceMinutes, String reason) {
        Objects.requireNonNull(noShowAt);
        Objects.requireNonNull(ownerId);

        if (status == ReservationStatus.NO_SHOW) {
            return;
        }
        if (arrivalGraceMinutes < 0) {
            throw new IllegalArgumentException("도착 유예시간은 0분 이상이어야 합니다.");
        }
        // 입장 확인이 없고 도착 유예시간이 끝난 시각 이후인 확정 예약만 노쇼 처리할 수 있다.
        if (status != ReservationStatus.CONFIRMED
                || checkedInAt != null
                || noShowAt.isBefore(reservationStartAt.plusMinutes(arrivalGraceMinutes))) {
            throw new IllegalStateException("노쇼 처리할 수 없는 예약입니다.");
        }

        this.status = ReservationStatus.NO_SHOW;
        this.noShowAt = noShowAt;
        this.noShowByMemberId = ownerId;
        this.noShowReason = reason;
    }
}
