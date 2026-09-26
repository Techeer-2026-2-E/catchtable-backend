package com.catchtable.store.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.OpeningWindow;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "business_hour")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessHour extends BaseTimeEntity {

    private static final int MINUTES_PER_DAY = 24 * 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Convert(converter = DayOfWeekConverter.class)
    @Column(name = "day_of_week", nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    @Column(name = "break_start_time")
    private LocalTime breakStartTime;

    @Column(name = "break_end_time")
    private LocalTime breakEndTime;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    private BusinessHour(Store store, DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closingTime,
                         LocalTime breakStartTime, LocalTime breakEndTime) {
        validate(openTime, closingTime, breakStartTime, breakEndTime);
        this.store = store;
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closingTime = closingTime;
        this.breakStartTime = breakStartTime;
        this.breakEndTime = breakEndTime;
    }

    public void changeHours(LocalTime openTime, LocalTime closingTime,
                            LocalTime breakStartTime, LocalTime breakEndTime) {
        validate(openTime, closingTime, breakStartTime, breakEndTime);
        this.openTime = openTime;
        this.closingTime = closingTime;
        this.breakStartTime = breakStartTime;
        this.breakEndTime = breakEndTime;
    }

    public void delete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean hasBreak() {
        return breakStartTime != null;
    }

    // 영업일(businessDate) 기준 실제 영업 구간
    public List<OpeningWindow> windowsOn(LocalDate businessDate) {
        LocalDateTime base = businessDate.atStartOfDay();
        int open = toMinutes(openTime);
        int close = offsetFromOpen(closingTime, open, true);

        if (!hasBreak()) {
            return List.of(new OpeningWindow(base.plusMinutes(open), base.plusMinutes(close)));
        }
        int breakStart = offsetFromOpen(breakStartTime, open, false);
        int breakEnd = offsetFromOpen(breakEndTime, open, false);
        return List.of(
                new OpeningWindow(base.plusMinutes(open), base.plusMinutes(breakStart)),
                new OpeningWindow(base.plusMinutes(breakEnd), base.plusMinutes(close))
        );
    }

    // 모든 시각을 "영업일 0시로부터 몇 분"으로 바꿔서 비교한다.
    // 여는 시각보다 이른 시각은 다음 날로 본다 (예: 18:00 오픈이면 02:00 → 26:00)
    private static void validate(LocalTime openTime, LocalTime closingTime,
                                 LocalTime breakStartTime, LocalTime breakEndTime) {
        if (openTime.equals(closingTime)) {
            throw new BusinessException(ErrorCode.INVALID_BUSINESS_HOUR);
        }
        if ((breakStartTime == null) != (breakEndTime == null)) {
            throw new BusinessException(ErrorCode.INVALID_BREAK_TIME);
        }
        if (breakStartTime == null) {
            return;
        }
        int open = toMinutes(openTime);
        int close = offsetFromOpen(closingTime, open, true);
        int breakStart = offsetFromOpen(breakStartTime, open, false);
        int breakEnd = offsetFromOpen(breakEndTime, open, false);
        if (!(open < breakStart && breakStart < breakEnd && breakEnd < close)) {
            throw new BusinessException(ErrorCode.INVALID_BREAK_TIME);
        }
    }

    private static int offsetFromOpen(LocalTime time, int open, boolean isClosing) {
        int minutes = toMinutes(time);
        boolean nextDay = isClosing ? minutes <= open : minutes < open;
        return nextDay ? minutes + MINUTES_PER_DAY : minutes;
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
