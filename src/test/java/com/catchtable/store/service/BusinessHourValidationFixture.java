package com.catchtable.store.service;

import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.Arrays;
import java.util.UUID;

/** 전용 매장만 생성/삭제한다. 회귀 및 before/after 성능 도구가 같은 데이터 구성을 사용한다. */
final class BusinessHourValidationFixture implements AutoCloseable {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final Instant NOW = Instant.parse("2030-01-07T03:00:00Z");
    static final LocalDate DATE = LocalDate.of(2030, 1, 14);

    final JdbcTemplate jdbc;
    final Long ownerId;
    final Long customerId;
    final Long storeId;
    final Long tableId;

    BusinessHourValidationFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String suffix = UUID.randomUUID().toString();
        ownerId = member("owner-" + suffix, "OWNER");
        customerId = member("customer-" + suffix, "CUSTOMER");
        storeId = jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'business hour validation', 'KOREAN', 'Seoul', false, 120, 30, 10) RETURNING id
                """, Long.class, ownerId);
        tableId = table(1);
        jdbc.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '08:00', '22:00' FROM generate_series(1, 7) day
                """, storeId);
    }

    Long table(int number) {
        return jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, ?, 4, 'ACTIVE') RETURNING id
                """, Long.class, storeId, number);
    }

    void reservation(Long targetTable, OffsetDateTime start, OffsetDateTime end) {
        jdbc.update("""
                INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at,
                    reservation_end_at, party_size, status, requested_at, confirmed_at)
                VALUES (?, ?, ?, ?, ?, 2, 'CONFIRMED', ?, ?)
                """, customerId, storeId, targetTable, start, end, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
    }

    // 날짜는 14일에 분산하고 동시간 예약은 별도 테이블로 배정한다. 경쟁 예약용 tableId는 비워 둔다.
    void activeReservations(int count) {
        for (int i = 0; i < count; i++) {
            OffsetDateTime start = at(DATE.plusDays(i % 14), 10);
            reservation(table(i + 2), start, start.plusHours(2));
        }
    }

    static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZONE).toOffsetDateTime();
    }

    static BusinessHourUpdateRequest weekly(String open, String close) {
        return new BusinessHourUpdateRequest(Arrays.stream(DayOfWeek.values())
                .map(day -> new BusinessHourRequest(day, LocalTime.parse(open), LocalTime.parse(close), null, null))
                .toList());
    }

    private Long member(String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'test-only', 'fixture', ?, ?) RETURNING id
                """, Long.class, name + "@example.com", "010-" + name.hashCode(), type);
    }

    @Override
    public void close() {
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM special_business_day WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store_table WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", customerId, ownerId);
    }
}
