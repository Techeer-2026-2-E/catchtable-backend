package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.SpecialBusinessDayRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 충돌 시 저장이 실제로 롤백되는지 봐야 하므로 @Transactional 없이 실제 커밋으로 검증한다.
@SpringBootTest
class SpecialBusinessDayServiceTest {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");
    private static final SpecialBusinessDayRequest CLOSED =
            new SpecialBusinessDayRequest(true, null, null, null, null);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SpecialBusinessDayService specialBusinessDayService;
    @Autowired private BusinessHourService businessHourService;
    @Autowired private ReservationService reservationService;

    private Long ownerId;
    private Long customerId;
    private Long storeId;
    private LocalDate date;

    // 매일 08:00~22:00 영업, 예약 시간은 2시간
    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ownerId = insertMember("owner-" + suffix + "@example.com", "OWNER");
        customerId = insertMember("customer-" + suffix + "@example.com", "CUSTOMER");
        storeId = jdbcTemplate.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, 'special day store', 'KOREAN', 'Seoul', false, 120, 30, 10)
                RETURNING id
                """, Long.class, ownerId);
        jdbcTemplate.update("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, 1, 4, 'ACTIVE')
                """, storeId);
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '08:00', '22:00' FROM generate_series(1, 7) AS day
                """, storeId);
        date = LocalDate.now(STORE_ZONE).plusDays(7);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM special_business_day WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM store_table WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM store WHERE id = ?", storeId);
        jdbcTemplate.update("DELETE FROM member WHERE id IN (?, ?)", customerId, ownerId);
    }

    @Test
    @DisplayName("예약이 있는 날을 휴무로 바꾸면 409이고 저장이 취소된다")
    void closingDayWithReservationRollsBack() {
        reserve(date, 10);

        assertErrorCode(() -> specialBusinessDayService.saveSpecialBusinessDay(storeId, date, CLOSED),
                ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);

        assertThat(rowCount(date)).isZero();
    }

    @Test
    @DisplayName("예약을 벗어나게 단축하면 409이고 기존 설정이 유지된다")
    void shorteningExistingDayRollsBack() {
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, open("08:00", "20:00"));
        reserve(date, 10);

        assertErrorCode(() -> specialBusinessDayService.saveSpecialBusinessDay(storeId, date, open("08:00", "11:00")),
                ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT closing_time FROM special_business_day WHERE store_id = ? AND business_date = ?",
                LocalTime.class, storeId, date)).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("예약을 포함하는 예외 영업은 저장된다")
    void openDayCoveringReservationIsSaved() {
        reserve(date, 10);

        assertThat(specialBusinessDayService.saveSpecialBusinessDay(storeId, date, open("09:00", "13:00")).closed())
                .isFalse();

        assertThat(rowCount(date)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 날짜를 다시 저장하면 행이 늘지 않고 수정된다")
    void savingTwiceUpdatesSameRow() {
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, CLOSED);
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, open("10:00", "20:00"));

        assertThat(rowCount(date)).isEqualTo(1);
        assertThat(specialBusinessDayService.getSpecialBusinessDays(storeId))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.closed()).isFalse();
                    assertThat(day.openTime()).isEqualTo(LocalTime.of(10, 0));
                });
    }

    @Test
    @DisplayName("삭제한 날짜는 다시 등록할 수 있다")
    void deletedDateCanBeSavedAgain() {
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, CLOSED);
        specialBusinessDayService.deleteSpecialBusinessDay(storeId, date);

        assertThat(specialBusinessDayService.getSpecialBusinessDays(storeId)).isEmpty();

        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, CLOSED);

        assertThat(specialBusinessDayService.getSpecialBusinessDays(storeId)).hasSize(1);
        assertThat(rowCount(date)).isEqualTo(2);
    }

    @Test
    @DisplayName("특정일로만 받은 예약이 있으면 설정을 삭제할 수 없다")
    void deletingDayThatReservationDependsOnRollsBack() {
        removeWeeklyHours(date.getDayOfWeek());
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, open("08:00", "22:00"));
        reserve(date, 10);

        assertErrorCode(() -> specialBusinessDayService.deleteSpecialBusinessDay(storeId, date),
                ErrorCode.BUSINESS_HOUR_HAS_RESERVATIONS);

        assertThat(specialBusinessDayService.getSpecialBusinessDays(storeId)).hasSize(1);
    }

    @Test
    @DisplayName("요일별 설정을 바꿔도 특정일로 받은 예약은 특정일 기준으로 검사한다")
    void replacingWeeklyHoursRespectsSpecialDay() {
        removeWeeklyHours(date.getDayOfWeek());
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, open("08:00", "22:00"));
        reserve(date, 10);

        List<BusinessHourRequest> otherDays = Arrays.stream(DayOfWeek.values())
                .filter(day -> day != date.getDayOfWeek())
                .map(day -> new BusinessHourRequest(day, LocalTime.of(8, 0), LocalTime.of(22, 0), null, null))
                .toList();

        assertThat(businessHourService.replaceBusinessHours(storeId, new BusinessHourUpdateRequest(otherDays)))
                .hasSize(6);
    }

    @Test
    @DisplayName("특정일 휴무에는 예약할 수 없고, 정기 휴무 요일도 특정일로 열면 예약할 수 있다")
    void reservationFollowsSpecialDay() {
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, CLOSED);
        assertThatThrownBy(() -> reserve(date, 10)).isInstanceOf(ResponseStatusException.class);

        LocalDate weeklyClosed = date.plusDays(1);
        removeWeeklyHours(weeklyClosed.getDayOfWeek());
        assertThatThrownBy(() -> reserve(weeklyClosed, 10)).isInstanceOf(ResponseStatusException.class);

        specialBusinessDayService.saveSpecialBusinessDay(storeId, weeklyClosed, open("08:00", "22:00"));
        reserve(weeklyClosed, 10);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservation WHERE store_id = ?", Long.class, storeId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("어제는 막고 오늘은 허용한다")
    void pastDateBoundary() {
        LocalDate today = LocalDate.now(STORE_ZONE);

        assertErrorCode(() -> specialBusinessDayService.saveSpecialBusinessDay(storeId, today.minusDays(1), CLOSED),
                ErrorCode.PAST_BUSINESS_DATE);
        assertThat(specialBusinessDayService.saveSpecialBusinessDay(storeId, today, CLOSED).closed()).isTrue();
    }

    @Test
    @DisplayName("없는 설정을 삭제하면 404")
    void deletingMissingDay() {
        assertErrorCode(() -> specialBusinessDayService.deleteSpecialBusinessDay(storeId, date),
                ErrorCode.SPECIAL_BUSINESS_DAY_NOT_FOUND);
    }

    @Test
    @DisplayName("DB는 같은 매장·날짜의 중복 행을 막는다")
    void uniqueIndexRejectsDuplicate() {
        specialBusinessDayService.saveSpecialBusinessDay(storeId, date, CLOSED);

        assertThatThrownBy(() -> insertRow(date, true, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB는 휴무인데 시각이 있는 행을 막는다")
    void checkRejectsClosedDayWithTimes() {
        assertThatThrownBy(() -> insertRow(date, true, LocalTime.of(10, 0), LocalTime.of(20, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB는 영업일인데 시각이 없는 행을 막는다")
    void checkRejectsOpenDayWithoutTimes() {
        assertThatThrownBy(() -> insertRow(date, false, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static SpecialBusinessDayRequest open(String open, String close) {
        return new SpecialBusinessDayRequest(false, LocalTime.parse(open), LocalTime.parse(close), null, null);
    }

    private void reserve(LocalDate reservationDate, int hour) {
        OffsetDateTime startAt = reservationDate.atTime(hour, 0).atZone(STORE_ZONE).toOffsetDateTime();
        reservationService.createReservation(customerId, new CreateReservationRequest(storeId, startAt, 2));
    }

    private void removeWeeklyHours(DayOfWeek day) {
        jdbcTemplate.update("DELETE FROM business_hour WHERE store_id = ? AND day_of_week = ?", storeId, day.getValue());
    }

    private Long rowCount(LocalDate businessDate) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM special_business_day WHERE store_id = ? AND business_date = ?",
                Long.class, storeId, businessDate);
    }

    private void insertRow(LocalDate businessDate, boolean closed, LocalTime openTime, LocalTime closingTime) {
        jdbcTemplate.update("""
                INSERT INTO special_business_day (store_id, business_date, closed, open_time, closing_time)
                VALUES (?, ?, ?, ?, ?)
                """, storeId, businessDate, closed, openTime, closingTime);
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private Long insertMember(String email, String userType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'member', ?, ?)
                RETURNING id
                """, Long.class, email, "010-" + email.hashCode(), userType);
    }
}
