package com.catchtable.waiting.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.store.entity.Store;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

// 웨이팅 엔티티
@Entity
@Table(
        name = "waiting",
        // 같은 매장·같은 날 대기번호 중복 방지 (DB 안전장치)
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_store_date_number",
                columnNames = {"store_id", "waiting_date", "wait_number"}
        ),
        // 중복 신청 조회용 인덱스
        indexes = @Index(
                name = "idx_waiting_member_store_date",
                columnList = "member_id, store_id, waiting_date"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Waiting extends BaseTimeEntity {

    // PK
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 신청 고객
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // 대상 매장
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // 웨이팅 날짜 (일자별 번호 발급 기준)
    @Column(name = "waiting_date", nullable = false)
    private LocalDate waitingDate;

    // 인원수
    @Column(name = "party_count", nullable = false)
    private int partyCount;

    // 대기번호
    @Column(name = "wait_number", nullable = false)
    private int waitNumber;

    // 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaitingStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 30)
    private WaitingEndReason endReason;

    @Column(name = "end_detail", length = 300)
    private String endDetail;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "called_at")
    private OffsetDateTime calledAt;

    @Column(name = "call_expires_at")
    private OffsetDateTime callExpiresAt;

    @Column(name = "call_count", nullable = false)
    private int callCount;

    // 응답 시각
    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    // 방문 의사
    @Enumerated(EnumType.STRING)
    @Column(name = "arrival_intent", length = 20)
    private ArrivalIntent arrivalIntent;

    // 입장 시각
    @Column(name = "entered_at")
    private OffsetDateTime enteredAt;

    private Waiting(Member member, Store store, LocalDate waitingDate, int partyCount, int waitNumber) {
        this.member = member;
        this.store = store;
        this.waitingDate = waitingDate;
        this.partyCount = partyCount;
        this.waitNumber = waitNumber;
        this.status = WaitingStatus.WAITING; // 최초 상태: 대기
    }

    // 생성 팩토리
    public static Waiting create(Member member, Store store, LocalDate waitingDate, int partyCount, int waitNumber) {
        return new Waiting(member, store, waitingDate, partyCount, waitNumber);
    }

    public WaitingEndHistory cancelByOwner(String reason, Long actorId, OffsetDateTime now) {
        String detail = validateDetail(reason);
        if (status == WaitingStatus.CANCELED) {
            return null;
        }
        if (!WaitingStatus.ACTIVE.contains(status)) {
            throw new BusinessException(ErrorCode.WAITING_NOT_CANCELLABLE);
        }
        return end(WaitingStatus.CANCELED, WaitingEndReason.OWNER_CANCELED, detail, actorId, now);
    }

    // 고객 취소
    public WaitingEndHistory cancelByCustomer(Long actorId, OffsetDateTime now) {
        // 이미 취소면 그대로
        if (status == WaitingStatus.CANCELED) {
            return null;
        }
        if (!WaitingStatus.ACTIVE.contains(status)) {
            throw new BusinessException(ErrorCode.WAITING_NOT_CANCELLABLE);
        }
        return end(WaitingStatus.CANCELED, WaitingEndReason.CUSTOMER_CANCELED, "고객 취소", actorId, now);
    }

    public WaitingEndHistory markNoShow(WaitingEndReason reason, String detail, Long actorId, OffsetDateTime now) {
        if (reason != WaitingEndReason.NO_RESPONSE && reason != WaitingEndReason.NO_SHOW) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        detail = validateDetail(detail);
        if (status != WaitingStatus.CALLED && status != WaitingStatus.CONFIRMED && status != WaitingStatus.NO_SHOW) {
            throw new BusinessException(ErrorCode.WAITING_NOT_TERMINABLE);
        }
        if (status == WaitingStatus.NO_SHOW && endReason == reason && Objects.equals(endDetail, detail)) {
            return null;
        }
        return end(WaitingStatus.NO_SHOW, reason, detail, actorId, now);
    }

    public void call(OffsetDateTime now, int limitMinutes) {
        if (status != WaitingStatus.WAITING && status != WaitingStatus.CALLED) {
            throw new BusinessException(ErrorCode.WAITING_NOT_CALLABLE);
        }
        status = WaitingStatus.CALLED;
        callCount++;
        calledAt = now;
        callExpiresAt = now.plusMinutes(limitMinutes);
        // 재호출 시 응답 초기화
        respondedAt = null;
        arrivalIntent = null;
    }

    // 방문 의사 응답
    public boolean respond(OffsetDateTime now) {
        if (status != WaitingStatus.CALLED) {
            throw new BusinessException(ErrorCode.WAITING_NOT_RESPONDABLE);
        }
        if (respondedAt != null) {
            return false;
        }
        // 만료 후 거절
        if (callExpiresAt != null && now.isAfter(callExpiresAt)) {
            throw new BusinessException(ErrorCode.WAITING_NOT_RESPONDABLE);
        }
        respondedAt = now;
        arrivalIntent = ArrivalIntent.COMING;
        return true;
    }

    // 입장 처리
    public boolean enter(OffsetDateTime now) {
        if (status == WaitingStatus.SEATED) {
            return false;
        }
        if (status != WaitingStatus.CALLED && status != WaitingStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.WAITING_NOT_ENTERABLE);
        }
        // 늦은 도착 허용
        status = WaitingStatus.SEATED;
        enteredAt = now;
        return true;
    }

    private WaitingEndHistory end(WaitingStatus nextStatus, WaitingEndReason reason, String detail,
                                  Long actorId, OffsetDateTime now) {
        WaitingEndHistory history = WaitingEndHistory.record(this, nextStatus, reason, detail, actorId, now);
        // 이미 종료된 건은 최초 종료 시각을 유지하고 사유만 정정한다.
        if (WaitingStatus.ACTIVE.contains(status)) {
            endedAt = now;
        }
        status = nextStatus;
        endReason = reason;
        endDetail = detail;
        return history;
    }

    private static String validateDetail(String detail) {
        if (detail == null || detail.isBlank() || detail.length() > 300) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return detail.strip();
    }
}
