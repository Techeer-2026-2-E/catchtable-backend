package com.catchtable.store.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name="store")
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

    private static final int DEFAULT_BOOKING_OPEN_DAYS = 30;
    private static final int DEFAULT_BOOKING_DEADLINE_MINUTES = 0;


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

    @Column(name = "booking_open_days", nullable = false)
    private int bookingOpenDays;

    @Column(name = "booking_deadline_minutes", nullable = false)
    private int bookingDeadlineMinutes;


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
        this.bookingOpenDays=DEFAULT_BOOKING_OPEN_DAYS;
        this.bookingDeadlineMinutes=DEFAULT_BOOKING_DEADLINE_MINUTES;
    }

    public void changeReservationPolicy(
            int durationMinutes, int slotMinutes, int arrivalGraceMinutes,
            int bookingOpenDays, int bookingDeadlineMinutes
    )
    {
        if(arrivalGraceMinutes>=durationMinutes)
        {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_POLICY);
        }
        this.reservationDurationMinutes = durationMinutes;
        this.reservationSlotMinutes = slotMinutes;
        this.arrivalGraceMinutes = arrivalGraceMinutes;
        this.bookingOpenDays = bookingOpenDays;
        this.bookingDeadlineMinutes = bookingDeadlineMinutes;
    }

    public void validateBookable(LocalDateTime startAt, LocalDateTime now)
    {
        if(!isBookingOpen(startAt, now))
        {
            throw new BusinessException(ErrorCode.BOOKING_NOT_OPEN_YET);
        }
        if(!isBeforeDeadline(startAt, now))
        {
            throw new BusinessException(ErrorCode.BOOKING_DEADLINE_PASSED);
        }
    }
    public boolean isBookingOpen(LocalDateTime startAt, LocalDateTime now)
    {
        LocalDate lastBookableDate=now.toLocalDate().plusDays(bookingOpenDays);
        return !startAt.toLocalDate().isAfter(lastBookableDate);
    }

    // 시작 bookingDeadlineMinutes분 전까지만 예약을 받는다
    public boolean isBeforeDeadline(LocalDateTime startAt, LocalDateTime now)
    {
        return !startAt.minusMinutes(bookingDeadlineMinutes).isBefore(now);
    }

    // 검색용: 예약 창이 열려 있고 마감 전인지
    public boolean isWithinBookingWindow(LocalDateTime startAt, LocalDateTime now)
    {
        return isBookingOpen(startAt, now) && isBeforeDeadline(startAt, now);
    }


}
