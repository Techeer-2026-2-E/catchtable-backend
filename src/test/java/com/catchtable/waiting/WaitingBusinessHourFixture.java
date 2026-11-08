package com.catchtable.waiting;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

// 웨이팅 신청은 영업 중에만 가능하므로, 테스트 매장에 영업시간을 넣고 빼는 공용 도구
public final class WaitingBusinessHourFixture {

    private WaitingBusinessHourFixture() {
    }

    // 지금 시각 ±1시간을 7요일 모두에 등록한다. 자정에 걸쳐도 전날 구간이 이어지므로 항상 영업 중이다.
    public static void openNow(JdbcTemplate jdbc, Clock clock, Long storeId) {
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        saveAllDays(jdbc, storeId, now.minusHours(1), now.plusHours(1));
    }

    public static void saveAllDays(JdbcTemplate jdbc, Long storeId, LocalTime open, LocalTime close) {
        for (DayOfWeek day : DayOfWeek.values()) {
            jdbc.update("""
                    INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                    VALUES (?, ?, ?, ?)
                    """, storeId, day.getValue(), open, close);
        }
    }

    // FK 때문에 매장을 지우기 전에 호출한다.
    public static void clear(JdbcTemplate jdbc, Long storeId) {
        jdbc.update("delete from special_business_day where store_id = ?", storeId);
        jdbc.update("delete from business_hour where store_id = ?", storeId);
    }
}
