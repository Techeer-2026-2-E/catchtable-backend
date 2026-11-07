package com.catchtable.store.entity;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.OpeningWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecialBusinessDayTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 25);

    private static LocalTime time(String value) {
        return LocalTime.parse(value);
    }

    private static OpeningWindow window(String start, String end) {
        return new OpeningWindow(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }

    private static SpecialBusinessDay openDay(String open, String close, String breakStart, String breakEnd) {
        SpecialBusinessDay day = SpecialBusinessDay.closedDay(null, DATE);
        day.changeToOpen(time(open), time(close), time(breakStart), time(breakEnd));
        return day;
    }

    @Test
    @DisplayName("휴무일은 영업 구간이 없다")
    void closedDayHasNoWindows() {
        assertThat(SpecialBusinessDay.closedDay(null, DATE).windows()).isEmpty();
    }

    @Test
    @DisplayName("예외 영업의 브레이크는 구간을 둘로 나눈다")
    void openDayWithBreak() {
        assertThat(openDay("10:00", "20:00", "15:00", "17:00").windows()).containsExactly(
                window("2026-12-25T10:00", "2026-12-25T15:00"),
                window("2026-12-25T17:00", "2026-12-25T20:00"));
    }

    @Test
    @DisplayName("자정을 넘기면 마감은 다음 날이다")
    void overnight() {
        SpecialBusinessDay day = SpecialBusinessDay.closedDay(null, DATE);
        day.changeToOpen(time("18:00"), time("02:00"), null, null);

        assertThat(day.windows()).containsExactly(window("2026-12-25T18:00", "2026-12-26T02:00"));
    }

    @Test
    @DisplayName("영업일을 휴무로 바꾸면 시각이 모두 지워진다")
    void changeToClosedClearsTimes() {
        SpecialBusinessDay day = openDay("10:00", "20:00", "15:00", "17:00");

        day.changeToClosed();

        assertThat(day.isClosed()).isTrue();
        assertThat(day.getOpenTime()).isNull();
        assertThat(day.getClosingTime()).isNull();
        assertThat(day.getBreakStartTime()).isNull();
        assertThat(day.getBreakEndTime()).isNull();
    }

    @Test
    @DisplayName("잘못된 시각이면 예외가 나고 기존 값은 그대로다")
    void invalidHoursKeepExistingValues() {
        SpecialBusinessDay day = SpecialBusinessDay.closedDay(null, DATE);

        assertThatThrownBy(() -> day.changeToOpen(time("10:00"), time("10:00"), null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_BUSINESS_HOUR));

        assertThat(day.isClosed()).isTrue();
        assertThat(day.getOpenTime()).isNull();
    }

    @Test
    @DisplayName("영업일인데 시각이 없으면 NPE가 아니라 잘못된 요청이다")
    void missingTimes() {
        SpecialBusinessDay day = SpecialBusinessDay.closedDay(null, DATE);

        assertThatThrownBy(() -> day.changeToOpen(null, time("20:00"), null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_BUSINESS_HOUR));
    }

    @Test
    @DisplayName("브레이크가 영업시간 밖이면 거절한다")
    void breakOutsideHours() {
        SpecialBusinessDay day = SpecialBusinessDay.closedDay(null, DATE);

        assertThatThrownBy(() -> day.changeToOpen(time("10:00"), time("20:00"), time("09:00"), time("11:00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_BREAK_TIME));
    }
}
