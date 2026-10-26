package com.catchtable.store.entity;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 18, 0);

    // 이용시간 120, 슬롯 30, 유예 10, 예약 가능 30일, 마감 60분 전
    private static Store store() {
        Store store = Store.builder()
                .reservationDurationMinutes(120)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();
        store.changeReservationPolicy(120, 30, 10, 30, 60);
        return store;
    }

    @Test
    @DisplayName("새 매장은 30일 뒤까지, 시작 직전까지 예약을 받는다")
    void defaultPolicy() {
        Store store = Store.builder()
                .reservationDurationMinutes(120)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();

        assertThat(store.getBookingOpenDays()).isEqualTo(30);
        assertThat(store.getBookingDeadlineMinutes()).isZero();
    }

    @Test
    @DisplayName("도착 유예가 이용시간 이상이면 정책을 바꿀 수 없다")
    void graceNotShorterThanDuration() {
        assertThatThrownBy(() -> store().changeReservationPolicy(60, 30, 60, 30, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_RESERVATION_POLICY);
    }

    @Test
    @DisplayName("오늘 + 30일 날짜까지는 시각과 상관없이 예약할 수 있다")
    void lastOpenDay() {
        assertThatCode(() -> store().validateBookable(LocalDateTime.of(2026, 10, 27, 23, 30), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("오늘 + 31일 날짜는 아직 예약을 받지 않는다")
    void afterOpenDays() {
        assertThatThrownBy(() -> store().validateBookable(LocalDateTime.of(2026, 10, 28, 0, 0), NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BOOKING_NOT_OPEN_YET);
    }

    @Test
    @DisplayName("시작 60분 전 정각까지는 예약할 수 있다")
    void exactlyAtDeadline() {
        assertThatCode(() -> store().validateBookable(LocalDateTime.of(2026, 9, 27, 19, 0), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("시작까지 60분이 안 남으면 마감이다")
    void afterDeadline() {
        assertThatThrownBy(() -> store().validateBookable(LocalDateTime.of(2026, 9, 27, 18, 30), NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BOOKING_DEADLINE_PASSED);
    }
}
