package com.catchtable.store.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name="store")
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="owner_id",nullable = false)
    private Member owner;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StoreCategory category;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(name = "waiting_available", nullable = false)
    private boolean waitingAvailable;

    @Column(name = "reservation_duration_minutes", nullable = false)
    private int reservationDurationMinutes;

    @Column(name = "reservation_slot_minutes", nullable = false)
    private int reservationSlotMinutes;

    @Column(name = "arrival_grace_minutes", nullable = false)
    private int arrivalGraceMinutes;


    @Builder
    private Store(Member owner, String name, StoreCategory category, String address,
                  int reservationDurationMinutes, int reservationSlotMinutes,
                  int arrivalGraceMinutes) {
        this.owner = owner;
        this.name = name;
        this.category = category;
        this.address = address;
        this.waitingAvailable = false;
        this.reservationDurationMinutes = reservationDurationMinutes;
        this.reservationSlotMinutes = reservationSlotMinutes;
        this.arrivalGraceMinutes = arrivalGraceMinutes;
    }
}
