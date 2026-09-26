package com.catchtable.store.entity;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.OpeningWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessHourTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);

    private static BusinessHour hour(String open, String close, String breakStart, String breakEnd) {
        return BusinessHour.builder()
                .dayOfWeek(DayOfWeek.FRIDAY)
                .openTime(LocalTime.parse(open))
                .closingTime(LocalTime.parse(close))
                .breakStartTime(breakStart == null ? null : LocalTime.parse(breakStart))
                .breakEndTime(breakEnd == null ? null : LocalTime.parse(breakEnd))
                .build();
    }

    private static OpeningWindow window(String start, String end) {
        return new OpeningWindow(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }

    @Test
    @DisplayName("브레이크 없는 영업은 구간 1개")
    void singleWindow() {
        assertThat(hour("11:00", "22:00", null, null).windowsOn(FRIDAY))
                .containsExactly(window("2026-10-02T11:00", "2026-10-02T22:00"));
    }

    @Test
    @DisplayName("브레이크가 있으면 구간 2개로 나뉜다")
    void splitByBreak() {
        assertThat(hour("11:00", "22:00", "15:00", "16:00").windowsOn(FRIDAY))
                .containsExactly(
                        window("2026-10-02T11:00", "2026-10-02T15:00"),
                        window("2026-10-02T16:00", "2026-10-02T22:00"));
    }

    @Test
    @DisplayName("마감이 오픈보다 이르면 다음 날 마감 (자정 넘김)")
    void overnight() {
        assertThat(hour("18:00", "02:00", null, null).windowsOn(FRIDAY))
                .containsExactly(window("2026-10-02T18:00", "2026-10-03T02:00"));
    }

    @Test
    @DisplayName("자정 넘김 영업에서 브레이크가 자정에 걸쳐도 된다")
    void overnightBreakAcrossMidnight() {
        assertThat(hour("18:00", "04:00", "23:30", "00:30").windowsOn(FRIDAY))
                .containsExactly(
                        window("2026-10-02T18:00", "2026-10-02T23:30"),
                        window("2026-10-03T00:30", "2026-10-03T04:00"));
    }

    @Test
    @DisplayName("00:00 마감은 다음 날 0시")
    void closeAtMidnight() {
        assertThat(hour("17:00", "00:00", null, null).windowsOn(FRIDAY))
                .containsExactly(window("2026-10-02T17:00", "2026-10-03T00:00"));
    }

    @Test
    @DisplayName("오픈과 마감이 같으면 거절")
    void rejectSameOpenAndClose() {
        assertThatThrownBy(() -> hour("10:00", "10:00", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BUSINESS_HOUR);
    }

    @Test
    @DisplayName("브레이크 시작·종료 중 하나만 있으면 거절")
    void rejectHalfBreak() {
        assertThatThrownBy(() -> hour("11:00", "22:00", "15:00", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BREAK_TIME);
    }

    @Test
    @DisplayName("브레이크가 영업시간 밖이거나 거꾸로면 거절")
    void rejectBreakOutsideHours() {
        assertThatThrownBy(() -> hour("11:00", "22:00", "21:00", "23:00"))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BREAK_TIME);
        assertThatThrownBy(() -> hour("11:00", "22:00", "16:00", "15:00"))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BREAK_TIME);
        assertThatThrownBy(() -> hour("11:00", "22:00", "11:00", "12:00"))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BREAK_TIME);
        assertThatThrownBy(() -> hour("18:00", "02:00", "01:00", "03:00"))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_BREAK_TIME);
    }
}
