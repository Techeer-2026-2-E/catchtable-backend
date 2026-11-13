package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.SpecialBusinessDayRequest;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.catchtable.store.service.BusinessHourValidationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(BusinessHourValidationSnapshotTest.Config.class)
class BusinessHourValidationSnapshotTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessHourService hours;
    @Autowired SpecialBusinessDayService specials;
    private BusinessHourValidationFixture fixture;
    private static final ThreadLocal<List<String>> QUERIES = new ThreadLocal<>();

    @TestConfiguration
    static class Config {
        @Bean @Primary Clock validationClock() { return Clock.fixed(NOW, ZONE); }
        @Bean HibernatePropertiesCustomizer captureValidationSql() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                if (QUERIES.get() != null) QUERIES.get().add(sql);
                return sql;
            });
        }
    }

    @BeforeEach void setUp() { fixture = new BusinessHourValidationFixture(jdbc); }
    @AfterEach void tearDown() { QUERIES.remove(); fixture.close(); }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 28})
    void configurationReadsAreBoundedRegardlessOfReservationCount(int count) {
        fixture.activeReservations(count);
        QUERIES.set(new ArrayList<>());
        hours.validateActiveReservationsCovered(fixture.storeId);
        List<String> sql = QUERIES.get();
        assertThat(sql.stream().filter(s -> s.contains(" from business_hour ")).count()).isEqualTo(count == 0 ? 0 : 1);
        assertThat(sql.stream().filter(s -> s.contains(" from special_business_day ")).count()).isEqualTo(count == 0 ? 0 : 1);
        assertThat(sql.stream().filter(s -> s.contains(" from reservation ")).count()).isEqualTo(1);
    }

    @Test
    void previousDayOvernightAndClosingBoundaryAreCovered() {
        hours.replaceBusinessHours(fixture.storeId, weekly("18:00", "03:00"));
        fixture.reservation(fixture.tableId, at(DATE, 1), at(DATE, 3));
        // 당일 휴무여도 전날의 영업은 이어진다는 기존 규칙을 유지한다.
        specials.saveSpecialBusinessDay(fixture.storeId, DATE, closed());
        assertThatCode(() -> hours.replaceBusinessHours(fixture.storeId, weekly("19:00", "03:00")))
                .doesNotThrowAnyException();
        assertConflict(() -> hours.replaceBusinessHours(fixture.storeId, weekly("19:00", "02:00")));
        assertThat(jdbc.queryForObject("SELECT closing_time FROM business_hour WHERE store_id = ? LIMIT 1",
                LocalTime.class, fixture.storeId)).isEqualTo(LocalTime.of(3, 0));
    }

    @Test
    void specialOvernightOpeningOverridesWeeklyAndBreakConflictRollsBack() {
        specials.saveSpecialBusinessDay(fixture.storeId, DATE.minusDays(1), open("20:00", "03:00"));
        fixture.reservation(fixture.tableId, at(DATE, 1), at(DATE, 3));
        hours.replaceBusinessHours(fixture.storeId, weekly("09:00", "21:00"));
        assertConflict(() -> specials.saveSpecialBusinessDay(fixture.storeId, DATE.minusDays(1),
                new SpecialBusinessDayRequest(false, LocalTime.of(20, 0), LocalTime.of(3, 0),
                        LocalTime.of(1, 30), LocalTime.of(2, 0))));
        assertThat(specials.getSpecialBusinessDays(fixture.storeId)).singleElement()
                .satisfies(day -> assertThat(day.breakStartTime()).isNull());
    }

    @Test
    void breakBoundaryAcceptedButSpanningBreakRejected() {
        fixture.reservation(fixture.tableId, at(DATE, 10), at(DATE, 12));
        specials.saveSpecialBusinessDay(fixture.storeId, DATE,
                new SpecialBusinessDayRequest(false, LocalTime.of(8, 0), LocalTime.of(22, 0),
                        LocalTime.of(12, 0), LocalTime.of(13, 0)));
        assertConflict(() -> specials.saveSpecialBusinessDay(fixture.storeId, DATE,
                new SpecialBusinessDayRequest(false, LocalTime.of(8, 0), LocalTime.of(22, 0),
                        LocalTime.of(11, 30), LocalTime.of(13, 0))));
        assertThat(specials.getSpecialBusinessDays(fixture.storeId)).singleElement()
                .satisfies(day -> assertThat(day.breakStartTime()).isEqualTo(LocalTime.NOON));
    }

    @Test
    void weeklyConflictRollsBackInsertUpdateAndSoftDeleteTogether() {
        jdbc.update("DELETE FROM business_hour WHERE store_id = ? AND day_of_week = 3", fixture.storeId);
        fixture.reservation(fixture.tableId, at(DATE, 10), at(DATE, 12));
        var before = jdbc.queryForList("SELECT * FROM business_hour WHERE store_id = ? ORDER BY id", fixture.storeId);
        var request = new BusinessHourUpdateRequest(List.of(
                new BusinessHourRequest(DayOfWeek.MONDAY, LocalTime.NOON, LocalTime.of(20, 0), null, null),
                new BusinessHourRequest(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0), null, null)));
        assertConflict(() -> hours.replaceBusinessHours(fixture.storeId, request));
        assertThat(jdbc.queryForList("SELECT * FROM business_hour WHERE store_id = ? ORDER BY id", fixture.storeId))
                .isEqualTo(before);
    }

    @Test
    void omittedWeeklyDayIsClosedAfterFlushAndRemovalRollsBack() {
        fixture.reservation(fixture.tableId, at(DATE, 10), at(DATE, 12));
        assertConflict(() -> hours.replaceBusinessHours(fixture.storeId, new BusinessHourUpdateRequest(List.of())));
        assertThat(hours.getBusinessHours(fixture.storeId)).hasSize(7);
    }

    @Test
    void deletedSpecialIsExcludedAndNextValidationSeesNewChanges() {
        specials.saveSpecialBusinessDay(fixture.storeId, DATE, open("09:00", "13:00"));
        fixture.reservation(fixture.tableId, at(DATE, 10), at(DATE, 12));
        specials.deleteSpecialBusinessDay(fixture.storeId, DATE);
        assertThat(specials.getSpecialBusinessDays(fixture.storeId)).isEmpty();
        // 삭제된 이전 설정을 다시 사용하거나 요청 밖에 snapshot을 캐시하면 이 충돌을 놓친다.
        assertConflict(() -> specials.saveSpecialBusinessDay(fixture.storeId, DATE, closed()));
        assertConflict(() -> hours.replaceBusinessHours(fixture.storeId, weekly("13:00", "22:00")));
        assertThat(specials.getSpecialBusinessDays(fixture.storeId)).isEmpty();
    }

    private static SpecialBusinessDayRequest closed() {
        return new SpecialBusinessDayRequest(true, null, null, null, null);
    }
    private static SpecialBusinessDayRequest open(String start, String end) {
        return new SpecialBusinessDayRequest(false, LocalTime.parse(start), LocalTime.parse(end), null, null);
    }
    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS));
    }
}
