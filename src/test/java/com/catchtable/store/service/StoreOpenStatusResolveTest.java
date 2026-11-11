package com.catchtable.store.service;

import com.catchtable.store.dto.OpenStatus;
import com.catchtable.store.dto.OpenStatusInfo;
import com.catchtable.store.dto.OpeningWindow;
import com.catchtable.store.entity.BusinessHour;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

// 영업 상태 계산 규칙을 DB·시계 없이 경계 시각까지 검증한다. 기준일은 2026-11-09(월)
class StoreOpenStatusResolveTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);

    private static LocalDateTime at(int dayOffset, int hour, int minute) {
        return MON.plusDays(dayOffset).atTime(hour, minute);
    }

    private static List<OpeningWindow> hours(LocalDate date, String open, String close, String breakStart, String breakEnd) {
        return BusinessHour.builder()
                .dayOfWeek(date.getDayOfWeek())
                .openTime(LocalTime.parse(open))
                .closingTime(LocalTime.parse(close))
                .breakStartTime(breakStart == null ? null : LocalTime.parse(breakStart))
                .breakEndTime(breakEnd == null ? null : LocalTime.parse(breakEnd))
                .build()
                .windowsOn(date);
    }

    // 매일 11:00~22:00, 브레이크 15:00~17:00
    private static final Function<LocalDate, List<OpeningWindow>> LUNCH_DINNER =
            date -> hours(date, "11:00", "22:00", "15:00", "17:00");

    private static void assertStatus(Function<LocalDate, List<OpeningWindow>> windowsOf, LocalDateTime now,
                                     OpenStatus status, LocalDateTime nextChangeAt) {
        assertThat(StoreOpenStatusService.resolve(now, windowsOf)).isEqualTo(new OpenStatusInfo(status, nextChangeAt));
    }

    @Test
    @DisplayName("오픈 1분 전은 영업 전이고 오픈 정각부터 영업 중이다")
    void openBoundary() {
        assertStatus(LUNCH_DINNER, at(0, 10, 59), OpenStatus.BEFORE_OPEN, at(0, 11, 0));
        assertStatus(LUNCH_DINNER, at(0, 11, 0), OpenStatus.OPEN, at(0, 15, 0));
    }

    @Test
    @DisplayName("브레이크 시작 1분 전까지 영업 중이고 시작 정각부터 브레이크다")
    void breakStartBoundary() {
        assertStatus(LUNCH_DINNER, at(0, 14, 59), OpenStatus.OPEN, at(0, 15, 0));
        assertStatus(LUNCH_DINNER, at(0, 15, 0), OpenStatus.BREAK, at(0, 17, 0));
    }

    @Test
    @DisplayName("브레이크 종료 1분 전까지 브레이크이고 종료 정각부터 영업 중이다")
    void breakEndBoundary() {
        assertStatus(LUNCH_DINNER, at(0, 16, 59), OpenStatus.BREAK, at(0, 17, 0));
        assertStatus(LUNCH_DINNER, at(0, 17, 0), OpenStatus.OPEN, at(0, 22, 0));
    }

    @Test
    @DisplayName("마감 1분 전까지 영업 중이고 마감 정각부터 영업 종료이며 다음 영업은 내일이다")
    void closingBoundary() {
        assertStatus(LUNCH_DINNER, at(0, 21, 59), OpenStatus.OPEN, at(0, 22, 0));
        assertStatus(LUNCH_DINNER, at(0, 22, 0), OpenStatus.CLOSED, at(1, 11, 0));
        assertStatus(LUNCH_DINNER, at(0, 23, 59), OpenStatus.CLOSED, at(1, 11, 0));
    }

    @Test
    @DisplayName("자정 직후 새벽은 오늘 오픈 전이다")
    void afterMidnightIsBeforeOpen() {
        assertStatus(LUNCH_DINNER, at(0, 0, 0), OpenStatus.BEFORE_OPEN, at(0, 11, 0));
    }

    // 매일 18:00~다음 날 02:00
    private static final Function<LocalDate, List<OpeningWindow>> OVER_MIDNIGHT =
            date -> hours(date, "18:00", "02:00", null, null);

    @Test
    @DisplayName("자정을 넘기는 영업은 어제 시작한 영업이 새벽 마감까지 이어진다")
    void overMidnight() {
        assertStatus(OVER_MIDNIGHT, at(0, 0, 0), OpenStatus.OPEN, at(0, 2, 0));
        assertStatus(OVER_MIDNIGHT, at(0, 1, 59), OpenStatus.OPEN, at(0, 2, 0));
        assertStatus(OVER_MIDNIGHT, at(0, 2, 0), OpenStatus.BEFORE_OPEN, at(0, 18, 0));
        assertStatus(OVER_MIDNIGHT, at(0, 17, 59), OpenStatus.BEFORE_OPEN, at(0, 18, 0));
        assertStatus(OVER_MIDNIGHT, at(0, 18, 0), OpenStatus.OPEN, at(1, 2, 0));
        assertStatus(OVER_MIDNIGHT, at(0, 23, 59), OpenStatus.OPEN, at(1, 2, 0));
    }

    @Test
    @DisplayName("자정을 넘기는 영업에 브레이크가 새벽에 걸려도 브레이크로 본다")
    void breakAfterMidnight() {
        // 매일 18:00~다음 날 03:00, 브레이크 다음 날 00:30~01:00
        Function<LocalDate, List<OpeningWindow>> windowsOf = date -> hours(date, "18:00", "03:00", "00:30", "01:00");

        assertStatus(windowsOf, at(0, 0, 29), OpenStatus.OPEN, at(0, 0, 30));
        assertStatus(windowsOf, at(0, 0, 30), OpenStatus.BREAK, at(0, 1, 0));
        assertStatus(windowsOf, at(0, 1, 0), OpenStatus.OPEN, at(0, 3, 0));
    }

    @Test
    @DisplayName("오늘이 휴무면 영업 종료이고 다음 영업 시작 시각을 알려준다")
    void closedToday() {
        Function<LocalDate, List<OpeningWindow>> windowsOf =
                date -> date.equals(MON) ? List.of() : hours(date, "11:00", "22:00", null, null);

        assertStatus(windowsOf, at(0, 9, 0), OpenStatus.CLOSED, at(1, 11, 0));
        assertStatus(windowsOf, at(0, 13, 0), OpenStatus.CLOSED, at(1, 11, 0));
    }

    @Test
    @DisplayName("며칠 뒤에 다시 여는 매장은 그 시작 시각을 알려준다")
    void reopensLater() {
        Set<LocalDate> openDates = Set.of(MON.plusDays(5));
        Function<LocalDate, List<OpeningWindow>> windowsOf =
                date -> openDates.contains(date) ? hours(date, "11:00", "22:00", null, null) : List.of();

        assertStatus(windowsOf, at(0, 12, 0), OpenStatus.CLOSED, at(5, 11, 0));
    }

    @Test
    @DisplayName("7일 안에 영업이 없으면 영업 종료이고 다음 시각은 없다")
    void noOpeningInSearchRange() {
        assertStatus(date -> List.of(), at(0, 12, 0), OpenStatus.CLOSED, null);
    }

    @Test
    @DisplayName("7일 뒤 영업일까지는 찾고 8일 뒤는 찾지 않는다")
    void searchRangeBoundary() {
        Function<LocalDate, List<OpeningWindow>> day7 =
                date -> date.equals(MON.plusDays(7)) ? hours(date, "11:00", "22:00", null, null) : List.of();
        Function<LocalDate, List<OpeningWindow>> day8 =
                date -> date.equals(MON.plusDays(8)) ? hours(date, "11:00", "22:00", null, null) : List.of();

        assertStatus(day7, at(0, 12, 0), OpenStatus.CLOSED, at(7, 11, 0));
        assertStatus(day8, at(0, 12, 0), OpenStatus.CLOSED, null);
    }

    @Test
    @DisplayName("영업일이 바뀌는 자정에 끊김 없이 이어지는 영업은 하나로 보고 마지막 마감 시각을 알려준다")
    void continuousAcrossBusinessDays() {
        // 월: 10:00~다음 날 00:00, 화: 00:00~03:00
        Function<LocalDate, List<OpeningWindow>> windowsOf = date -> {
            if (date.equals(MON)) {
                return hours(date, "10:00", "00:00", null, null);
            }
            if (date.equals(MON.plusDays(1))) {
                return hours(date, "00:00", "03:00", null, null);
            }
            return List.of();
        };

        assertStatus(windowsOf, at(0, 23, 0), OpenStatus.OPEN, at(1, 3, 0));
    }
}
