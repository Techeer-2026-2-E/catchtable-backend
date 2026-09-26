package com.catchtable.store.service;

import com.catchtable.store.entity.BusinessHour;
import com.catchtable.store.repository.BusinessHourRepository;
import com.catchtable.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 예약·웨이팅이 호출할 조회(isOpen, isOpenBetween)만 DB 없이 검증
class BusinessHourServiceTest {

    private static final Long STORE_ID = 1L;

    private final BusinessHourRepository businessHourRepository = mock(BusinessHourRepository.class);
    private final BusinessHourService service =
            new BusinessHourService(mock(StoreRepository.class), businessHourRepository);

    // 금 18:00~02:00 (브레이크 23:00~23:30), 토 휴무, 일 11:00~21:00 (브레이크 15:00~16:00)
    @BeforeEach
    void setUp() {
        when(businessHourRepository.findByStoreIdAndDayOfWeek(eq(STORE_ID), any())).thenReturn(Optional.empty());
        stub(DayOfWeek.FRIDAY, "18:00", "02:00", "23:00", "23:30");
        stub(DayOfWeek.SUNDAY, "11:00", "21:00", "15:00", "16:00");
    }

    private void stub(DayOfWeek day, String open, String close, String breakStart, String breakEnd) {
        BusinessHour hour = BusinessHour.builder()
                .dayOfWeek(day)
                .openTime(LocalTime.parse(open))
                .closingTime(LocalTime.parse(close))
                .breakStartTime(LocalTime.parse(breakStart))
                .breakEndTime(LocalTime.parse(breakEnd))
                .build();
        when(businessHourRepository.findByStoreIdAndDayOfWeek(STORE_ID, day)).thenReturn(Optional.of(hour));
    }

    private static LocalDateTime at(String dateTime) {
        return LocalDateTime.parse(dateTime);
    }

    @Test
    @DisplayName("휴무인 토요일이어도 새벽은 금요일 영업의 연장이라 열려 있다")
    void openAfterMidnightOfPreviousDay() {
        assertThat(service.isOpen(STORE_ID, at("2026-10-03T01:00"))).isTrue();   // 토 01:00
        assertThat(service.isOpen(STORE_ID, at("2026-10-03T02:00"))).isFalse();  // 마감 시각 정각은 닫힘
        assertThat(service.isOpen(STORE_ID, at("2026-10-03T12:00"))).isFalse();  // 토 낮은 휴무
    }

    @Test
    @DisplayName("오픈 시각 정각은 열림, 브레이크 중은 닫힘")
    void boundaries() {
        assertThat(service.isOpen(STORE_ID, at("2026-10-04T11:00"))).isTrue();
        assertThat(service.isOpen(STORE_ID, at("2026-10-04T15:30"))).isFalse();
        assertThat(service.isOpen(STORE_ID, at("2026-10-04T10:59"))).isFalse();
    }

    @Test
    @DisplayName("이용시간 전체가 한 영업 구간 안에 있어야 한다")
    void openBetween() {
        // 일 13:30~15:00 → 브레이크 직전에 끝나서 가능
        assertThat(service.isOpenBetween(STORE_ID, at("2026-10-04T13:30"), at("2026-10-04T15:00"))).isTrue();
        // 일 14:00~15:30 → 브레이크에 걸침
        assertThat(service.isOpenBetween(STORE_ID, at("2026-10-04T14:00"), at("2026-10-04T15:30"))).isFalse();
        // 금 23:30~01:00 → 자정을 넘기지만 한 구간 안
        assertThat(service.isOpenBetween(STORE_ID, at("2026-10-02T23:30"), at("2026-10-03T01:00"))).isTrue();
        // 토 01:00~02:30 → 마감(02:00)을 넘김
        assertThat(service.isOpenBetween(STORE_ID, at("2026-10-03T01:00"), at("2026-10-03T02:30"))).isFalse();
    }
}
