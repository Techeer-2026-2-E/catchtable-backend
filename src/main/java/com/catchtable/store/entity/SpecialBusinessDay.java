package com.catchtable.store.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.OpeningWindow;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

// 특정 날짜 하루에만 적용되는 예외 영업. 이 날짜에는 요일별 설정(BusinessHour)보다 우선한다.
@Entity
@Table(name = "special_business_day")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpecialBusinessDay extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "closing_time")
    private LocalTime closingTime;

    @Column(name = "break_start_time")
    private LocalTime breakStartTime;

    @Column(name = "break_end_time")
    private LocalTime breakEndTime;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    private SpecialBusinessDay(Store store, LocalDate businessDate, boolean closed, LocalTime openTime,
                               LocalTime closingTime, LocalTime breakStartTime, LocalTime breakEndTime) {
        this.store = store;
        this.businessDate = businessDate;
        this.closed = closed;
        this.openTime = openTime;
        this.closingTime = closingTime;
        this.breakStartTime = breakStartTime;
        this.breakEndTime = breakEndTime;
    }

    // 휴무일은 시각을 받지 않으므로 "휴무인데 시각이 있는" 객체를 만들 수 없다.
    public static SpecialBusinessDay closedDay(Store store, LocalDate businessDate) {
        return new SpecialBusinessDay(store, businessDate, true, null, null, null, null);
    }

    public void changeToClosed() {
        this.closed = true;
        this.openTime = null;
        this.closingTime = null;
        this.breakStartTime = null;
        this.breakEndTime = null;
    }

    public void changeToOpen(LocalTime openTime, LocalTime closingTime,
                             LocalTime breakStartTime, LocalTime breakEndTime) {
        // 값이 잘못되면 기존 값을 그대로 두도록 먼저 검증한다.
        calculateWindows(businessDate, openTime, closingTime, breakStartTime, breakEndTime);
        this.closed = false;
        this.openTime = openTime;
        this.closingTime = closingTime;
        this.breakStartTime = breakStartTime;
        this.breakEndTime = breakEndTime;
    }

    public void delete() {
        this.deletedAt = OffsetDateTime.now();
    }

    // 이 날짜의 실제 영업 구간. 휴무일이면 빈 목록이다.
    public List<OpeningWindow> windows() {
        if (closed) {
            return List.of();
        }
        return calculateWindows(businessDate, openTime, closingTime, breakStartTime, breakEndTime);
    }

    private static List<OpeningWindow> calculateWindows(LocalDate businessDate, LocalTime openTime,
                                                        LocalTime closingTime, LocalTime breakStartTime,
                                                        LocalTime breakEndTime) {
        if (openTime == null || closingTime == null) {
            throw new BusinessException(ErrorCode.INVALID_BUSINESS_HOUR);
        }
        // 요일별 설정과 규칙이 갈라지지 않도록 검증과 구간 계산은 BusinessHour에 맡긴다.
        return BusinessHour.windowsOn(businessDate, openTime, closingTime, breakStartTime, breakEndTime);
    }
}
