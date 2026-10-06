package com.catchtable.waiting.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.member.entity.Member;
import com.catchtable.store.entity.Store;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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
}