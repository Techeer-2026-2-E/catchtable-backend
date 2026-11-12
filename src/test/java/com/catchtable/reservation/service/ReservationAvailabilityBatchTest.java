package com.catchtable.reservation.service;

import com.catchtable.payment.PaymentTestData;
import com.catchtable.reservation.dto.AvailabilityResponse;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import com.catchtable.store.service.BusinessHourService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.catchtable.reservation.service.ReservationAvailabilityBatchTest$SqlCapture")
@Transactional
class ReservationAvailabilityBatchTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T09:00:00+09:00");
    private static final LocalDate DATE = NOW.toLocalDate();
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreTableRepository tables;
    @Autowired StoreRepository stores;
    @Autowired BusinessHourService hours;
    @Autowired EntityManager em;
    private PaymentTestData data;
    private Long memberId;
    private Long storeId;
    private Long tableId;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        storeId = data.store(data.member("OWNER"));
        tableId = jdbc.queryForObject("SELECT id FROM store_table WHERE store_id = ?", Long.class, storeId);
    }

    @ParameterizedTest
    @CsvSource({"-1000,0", "0,1", "1000,1"})
    void unprotectedHoldExpiresExactlyAtBoundary(long nanosAfterExpiry, long expected) {
        Long hold = data.hold(memberId, storeId, 0);
        jdbc.update("UPDATE reservation SET requested_at = ?, hold_expires_at = ? WHERE id = ?",
                NOW.minusMinutes(5), NOW, hold);
        OffsetDateTime at = NOW.plusNanos(nanosAfterExpiry);
        List<OffsetDateTime> starts = List.of(NOW.withHour(18), NOW.withHour(17), NOW.withHour(18).plusMinutes(30),
                NOW.withHour(19), NOW.withHour(18));
        assertThat(tables.countAvailableBySlots(storeId, 2, starts, 60, at))
                .containsExactly(expected, 1L, expected, 1L, expected);
        assertParity(starts, 2, at);
    }

    @ParameterizedTest
    @CsvSource({"READY,false", "TIMEOUT,false", "DONE,false", "READY,true", "TIMEOUT,true", "DONE,true"})
    void approvalOrQuarantineContinuesToOccupyAfterOriginalExpiry(String status, boolean quarantine) {
        Long hold = data.hold(memberId, storeId, 0);
        Long payment = data.payment(memberId, hold, status, 0);
        if (quarantine) {
            jdbc.update("UPDATE reservation SET approval_quarantined = TRUE WHERE id = ?", hold);
        } else {
            data.protect(hold, payment, "batch-key-" + payment, NOW.minusMinutes(10));
        }
        List<OffsetDateTime> starts = List.of(NOW.withHour(17), NOW.withHour(18), NOW.withHour(19));
        assertThat(tables.countAvailableBySlots(storeId, 2, starts, 60, NOW)).containsExactly(1L, 0L, 1L);
        assertParity(starts, 2, NOW);
        // 조회가 선점을 만료시키거나 보호를 해제하지 않는다.
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, hold))
                .isEqualTo("PENDING_PAYMENT");
    }

    @ParameterizedTest
    @CsvSource({"CONFIRMED,0", "COMPLETED,0", "NO_SHOW,0", "CANCELLED,1", "EXPIRED,1"})
    void reservationStatusesAndTouchingEndpointsMatchSingleQuery(String status, long expected) {
        Long hold = data.hold(memberId, storeId, 0);
        jdbc.update("UPDATE reservation SET status = ?, confirmed_at = CASE WHEN ? IN ('CONFIRMED','COMPLETED','NO_SHOW') THEN requested_at ELSE NULL END WHERE id = ?",
                status, status, hold);
        List<OffsetDateTime> starts = List.of(NOW.withHour(19), NOW.withHour(18), NOW.withHour(17), NOW.withHour(17).plusMinutes(30));
        assertThat(tables.countAvailableBySlots(storeId, 2, starts, 60, NOW)).containsExactly(1L, expected, 1L, expected);
        assertParity(starts, 2, NOW);
    }

    @Test
    void tableCapacityStatusOtherStoresAndEmptyInputMatchSingleQuery() {
        jdbc.update("UPDATE store_table SET min_capacity = 2, capacity = 4 WHERE id = ?", tableId);
        jdbc.update("INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status) VALUES (?, 2, 1, 8, 'INACTIVE')", storeId);
        data.store(data.member("OWNER"));
        for (int party : List.of(1, 2, 4, 5)) {
            List<OffsetDateTime> starts = List.of(NOW.plusHours(1), NOW.plusHours(2));
            assertThat(tables.countAvailableBySlots(storeId, party, starts, 60, NOW))
                    .containsOnly(party >= 2 && party <= 4 ? 1L : 0L);
            assertParity(starts, party, NOW);
        }
        assertThat(tables.countAvailableBySlots(storeId, 2, List.of(), 60, NOW)).isEmpty();
        jdbc.update("DELETE FROM store_table WHERE store_id = ?", storeId);
        assertThat(tables.countAvailableBySlots(storeId, 2, List.of(NOW, NOW.plusHours(1)), 60, NOW))
                .containsExactly(0L, 0L);
    }

    @Test
    void specialOpeningBreakDeadlineAndSlotOrderingArePreserved() {
        weekly(DATE, "08:00", "22:00");
        jdbc.update("UPDATE store SET booking_deadline_minutes = 60 WHERE id = ?", storeId);
        jdbc.update("INSERT INTO special_business_day (store_id, business_date, closed, open_time, closing_time, break_start_time, break_end_time) VALUES (?, ?, false, '09:00', '15:00', '12:00', '13:00')", storeId, DATE);
        AvailabilityResponse response = service(NOW).getAvailability(storeId, DATE, 2);
        assertThat(response.slots()).extracting(s -> s.reservationStartAt().toLocalTime().toString())
                .containsExactly("10:00", "10:30", "11:00", "13:00", "13:30", "14:00");
        assertThat(response.slots()).allMatch(s -> s.available() && s.availableTableCount() == 1);
        assertThat(service(NOW.plusNanos(1000)).getAvailability(storeId, DATE, 2).slots())
                .extracting(s -> s.reservationStartAt().toLocalTime().toString())
                .containsExactly("10:30", "11:00", "13:00", "13:30", "14:00");
        assertParity(response.slots().stream().map(AvailabilityResponse.Slot::reservationStartAt).toList(), 2, NOW);
    }

    @Test
    void closedSpecialDayStillIncludesPreviousBusinessDaysOvernightSlots() {
        weekly(DATE.minusDays(1), "18:00", "02:00");
        weekly(DATE, "09:00", "22:00");
        jdbc.update("INSERT INTO special_business_day (store_id, business_date, closed) VALUES (?, ?, true)", storeId, DATE);
        assertThat(service(NOW.minusDays(1)).getAvailability(storeId, DATE, 2).slots())
                .extracting(s -> s.reservationStartAt().toLocalTime().toString())
                .containsExactly("00:00", "00:30", "01:00");
        jdbc.update("INSERT INTO special_business_day (store_id, business_date, closed) VALUES (?, ?, true)", storeId, DATE.minusDays(1));
        em.clear();
        assertThat(service(NOW.minusDays(1)).getAvailability(storeId, DATE, 2).slots()).isEmpty();
    }

    @Test
    void overlappingBusinessDaysDeduplicateAndKeepEndAcrossMidnight() {
        weekly(DATE.minusDays(1), "18:00", "02:00");
        weekly(DATE, "00:00", "02:00");
        AvailabilityResponse response = service(NOW.minusDays(1)).getAvailability(storeId, DATE, 2);
        assertThat(response.slots()).extracting(s -> s.reservationStartAt().toLocalTime().toString())
                .containsExactly("00:00", "00:30", "01:00");
        jdbc.update("UPDATE business_hour SET open_time = '22:00', closing_time = '02:00' WHERE store_id = ? AND day_of_week = ?", storeId, DATE.getDayOfWeek().getValue());
        em.clear();
        assertThat(service(NOW.minusDays(1)).getAvailability(storeId, DATE, 2).slots())
                .extracting(s -> s.reservationStartAt().toLocalTime().toString())
                .containsExactly("00:00", "00:30", "01:00", "22:00", "22:30", "23:00", "23:30");
    }

    @Test
    void manySlotsExecuteOneCountSqlAndClosedDayExecutesNone() {
        weekly(DATE, "09:00", "23:00");
        SqlCapture.start();
        try {
            assertThat(service(NOW).getAvailability(storeId, DATE, 2).slots()).hasSize(27);
            assertThat(SqlCapture.counts()).hasSize(1);
        } finally {
            SqlCapture.stop();
        }
        SqlCapture.start();
        try {
            assertThat(service(NOW).getAvailability(storeId, DATE.plusDays(2), 2).slots()).isEmpty();
            assertThat(tables.countAvailableBySlots(storeId, 2, List.of(), 60, NOW)).isEmpty();
            assertThat(SqlCapture.counts()).isEmpty();
        } finally {
            SqlCapture.stop();
        }
    }

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();
        static void start() { SQL.set(new ArrayList<>()); }
        static List<String> counts() {
            return SQL.get().stream().filter(sql -> sql.contains("tstzrange")).toList();
        }
        static void stop() { SQL.remove(); }
        @Override public String inspect(String sql) {
            if (SQL.get() != null) SQL.get().add(sql);
            return sql;
        }
    }

    private void assertParity(List<OffsetDateTime> starts, int party, OffsetDateTime now) {
        List<Long> original = starts.stream().map(start -> tables.countAvailable(storeId, party, start, start.plusHours(1), now)).toList();
        assertThat(tables.countAvailableBySlots(storeId, party, starts, 60, now)).isEqualTo(original);
    }

    private ReservationAvailabilityService service(OffsetDateTime at) {
        return new ReservationAvailabilityService(stores, tables, hours,
                Clock.fixed(at.toInstant(), ZoneId.of("Asia/Seoul")));
    }

    private void weekly(LocalDate date, String open, String close) {
        jdbc.update("INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time) VALUES (?, ?, CAST(? AS TIME), CAST(? AS TIME))", storeId, date.getDayOfWeek().getValue(), open, close);
    }
}
