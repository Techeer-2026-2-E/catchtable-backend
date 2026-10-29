package com.catchtable.reservation.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
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
import java.time.temporal.ChronoUnit;
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

    @Column(name = "deposit_amount_snapshot", updatable = false)
    private Long depositAmountSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_policy_snapshot", length = 20, updatable = false)
    private RefundPolicy refundPolicySnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;

    @Column(name = "approval_started_at")
    private OffsetDateTime approvalStartedAt;

    @Column(name = "approval_payment_id")
    private Long approvalPaymentId;

    @Column(name = "approval_order_id", length = 64)
    private String approvalOrderId;

    @Column(name = "approval_payment_key", length = 200)
    private String approvalPaymentKey;

    @Column(name = "approval_quarantined", nullable = false)
    private boolean approvalQuarantined;

    @Column(name = "refund_in_progress", nullable = false)
    private boolean refundInProgress;

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
            OffsetDateTime requestedAt
    ) {
        this.member = Objects.requireNonNull(member);
        this.store = Objects.requireNonNull(store);
        this.tableId = Objects.requireNonNull(tableId);
        this.reservationStartAt = Objects.requireNonNull(reservationStartAt);
        this.reservationEndAt = Objects.requireNonNull(reservationEndAt);
        this.requestedAt = Objects.requireNonNull(requestedAt);

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
        Reservation reservation = new Reservation(
                member,
                store,
                tableId,
                reservationStartAt,
                reservationEndAt,
                partySize,
                confirmedAt
        );
        reservation.status = ReservationStatus.CONFIRMED;
        reservation.confirmedAt = confirmedAt;
        reservation.depositAmountSnapshot = 0L;
        return reservation;
    }

    public static Reservation held(
            Member member,
            Store store,
            Long tableId,
            OffsetDateTime reservationStartAt,
            OffsetDateTime reservationEndAt,
            int partySize,
            OffsetDateTime requestedAt
    ) {
        // PostgreSQL의 마이크로초 정밀도와 맞춰 생성 응답과 재조회 만료시간을 유지한다.
        requestedAt = Objects.requireNonNull(requestedAt).truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = new Reservation(
                member, store, tableId, reservationStartAt, reservationEndAt, partySize, requestedAt);
        reservation.status = ReservationStatus.PENDING_PAYMENT;
        reservation.holdExpiresAt = requestedAt.plusMinutes(5);
        return reservation;
    }

    /** 매장 정책 원본을 확인한 생성 호출부에서만 최초 예약금 조건을 전달한다. */
    public static Reservation held(
            Member member, Store store, Long tableId,
            OffsetDateTime reservationStartAt, OffsetDateTime reservationEndAt,
            int partySize, OffsetDateTime requestedAt, long depositAmount, RefundPolicy refundPolicy
    ) {
        if (depositAmount <= 0 || refundPolicy == null) {
            throw new IllegalArgumentException("예약금과 환불 정책이 올바르지 않습니다.");
        }
        Reservation reservation = held(member, store, tableId, reservationStartAt, reservationEndAt,
                partySize, requestedAt);
        reservation.depositAmountSnapshot = depositAmount;
        reservation.refundPolicySnapshot = refundPolicy;
        return reservation;
    }

    public ReservationStatus getStatusAt(OffsetDateTime now) {
        Objects.requireNonNull(now);
        if (status == ReservationStatus.PENDING_PAYMENT && approvalPaymentId == null && !approvalQuarantined
                && !holdExpiresAt.isAfter(now)) {
            return ReservationStatus.EXPIRED;
        }
        return status;
    }

    public void expireHold(OffsetDateTime now) {
        status = getStatusAt(now);
    }

    public void releaseHold(OffsetDateTime now) {
        if (approvalPaymentId != null || approvalQuarantined) {
            throw new IllegalStateException("승인 결과 확인 전에는 선점을 해제할 수 없습니다.");
        }
        expireHold(now);
        if (status == ReservationStatus.EXPIRED || status == ReservationStatus.CANCELLED) {
            return;
        }
        if (status != ReservationStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("결제 대기 선점만 해제할 수 있습니다.");
        }
        status = ReservationStatus.CANCELLED;
        cancelledAt = now;
        cancellationActor = CancellationActor.CUSTOMER;
        cancellationReason = "결제 전 선점 취소";
    }

    public boolean matchesPaymentApproval(Long paymentId, String orderId, String paymentKey) {
        return Objects.equals(approvalPaymentId, paymentId)
                && Objects.equals(approvalOrderId, orderId)
                && Objects.equals(approvalPaymentKey, paymentKey);
    }

    public void beginPaymentApproval(Long paymentId, String orderId, String paymentKey, OffsetDateTime now) {
        if (approvalQuarantined) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        if (approvalPaymentId != null) {
            if (!matchesPaymentApproval(paymentId, orderId, paymentKey)) {
                throw new BusinessException(ErrorCode.PAYMENT_MISMATCH);
            }
            return;
        }
        if (getStatusAt(now) != ReservationStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        approvalStartedAt = now.truncatedTo(ChronoUnit.MICROS);
        approvalPaymentId = Objects.requireNonNull(paymentId);
        approvalOrderId = Objects.requireNonNull(orderId);
        approvalPaymentKey = Objects.requireNonNull(paymentKey);
    }

    public boolean confirmPayment(Long paymentId, String orderId, String paymentKey, OffsetDateTime now) {
        if (approvalStartedAt == null || !approvalStartedAt.isBefore(holdExpiresAt)
                || !matchesPaymentApproval(paymentId, orderId, paymentKey)) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        if (status == ReservationStatus.CONFIRMED) {
            return false;
        }
        if (status != ReservationStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
        }
        status = ReservationStatus.CONFIRMED;
        confirmedAt = now;
        return true;
    }

    public void clearPaymentApproval() {
        approvalStartedAt = null;
        approvalPaymentId = null;
        approvalOrderId = null;
        approvalPaymentKey = null;
    }

    // 기능63 재확인 후 격리 해제 (최종 결과 확인 시에만)
    public void clearApprovalQuarantine() {
        approvalQuarantined = false;
    }

    public void validateModifiable() {
        requireNoRefundInProgress();
        if (status != ReservationStatus.CONFIRMED || checkedInAt != null) {
            throw new IllegalStateException("변경할 수 없는 예약입니다.");
        }
    }

    public void changeSchedule(Long tableId, OffsetDateTime startAt, OffsetDateTime endAt,
                               int partySize, OffsetDateTime now) {
        validateModifiable();
        boolean startChanged = !startAt.isEqual(reservationStartAt);
        if (tableId == null || tableId <= 0 || partySize <= 0
                || (startChanged && !startAt.isAfter(now)) || !startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("예약 변경 값이 올바르지 않습니다.");
        }
        this.tableId = tableId;
        this.reservationStartAt = startAt;
        this.reservationEndAt = endAt;
        this.partySize = partySize;
    }

    // 점주는 현재 구현상 시작 시각이 지나도 입장 확인 전이면 취소할 수 있다.
    public void cancelByOwner(OffsetDateTime cancelledAt, String reason) {
        Objects.requireNonNull(cancelledAt);
        requireNoRefundInProgress();

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

    //예약 시작과 같아도 취소 불가
    public void cancelByCustomer(OffsetDateTime cancelledAt)
    {
        Objects.requireNonNull(cancelledAt);
        requireNoRefundInProgress();

        if(status==ReservationStatus.CANCELLED)
        {
            return;
        }
        if (status != ReservationStatus.CONFIRMED || checkedInAt != null) {
            throw new IllegalStateException("취소할 수 없는 예약입니다.");
        }
        if (!cancelledAt.isBefore(reservationStartAt)) {
            throw new IllegalStateException("예약 시작 시각 이후에는 취소할 수 없습니다.");
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        this.cancellationActor = CancellationActor.CUSTOMER;
    }

    public void confirmVisit(OffsetDateTime checkedInAt, Long ownerId) {
        Objects.requireNonNull(checkedInAt);
        Objects.requireNonNull(ownerId);
        requireNoRefundInProgress();

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
        requireNoRefundInProgress();

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

    public void beginRefund() {
        requireNoRefundInProgress();
        refundInProgress = true;
    }

    public void finishRefund() {
        refundInProgress = false;
    }

    public void requireNoRefundInProgress() {
        if (refundInProgress) throw new BusinessException(ErrorCode.REFUND_DATA_NOT_READY);
    }

    // 최초 유효 요청에서 검사한 취소 의도만 정산한다. 재확인 시 현재 날짜로 재검사하지 않는다.
    public boolean cancelAfterRefund(CancellationActor actor, String reason, OffsetDateTime now) {
        if (status == ReservationStatus.CANCELLED) return false;
        if (!refundInProgress || status != ReservationStatus.CONFIRMED || checkedInAt != null
                || actor == CancellationActor.SYSTEM) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        status = ReservationStatus.CANCELLED;
        cancelledAt = now;
        cancellationActor = actor;
        cancellationReason = actor == CancellationActor.OWNER ? reason : null;
        return true;
    }
}
