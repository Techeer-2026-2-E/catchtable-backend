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





}
