package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.dto.SpecialBusinessDayRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 영업시간을 바꿀 때 오늘 접수된 웨이팅과 충돌하는지 검사한다.
// 충돌 시 저장이 실제로 롤백되는지 봐야 하므로 @Transactional 없이 실제 커밋으로 검증한다.
@SpringBootTest
class BusinessHourWaitingConflictTest {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BusinessHourService businessHourService;
    @Autowired private SpecialBusinessDayService specialBusinessDayService;

    private Long ownerId;
    private Long customerId;
    private Long storeId;
    private LocalDate today;
    private LocalTime now;

    // 매일 지금 시각 ±1시간 영업 → 지금은 영업 중
    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ownerId = insertMember("owner-" + suffix + "@example.com", "OWNER");
        customerId = insertMember("customer-" + suffix + "@example.com", "CUSTOMER");
        storeId = jdbc.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, 'waiting conflict store', 'KOREAN', 'Seoul', true, 90, 30, 10)
                RETURNING id
                """, Long.class, ownerId);
        today = LocalDate.now(STORE_ZONE);
        now = LocalTime.now(STORE_ZONE).truncatedTo(ChronoUnit.MINUTES);
        insertWeeklyHours(now.minusHours(1), now.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM waiting WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM special_business_day WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", customerId, ownerId);
    }

    @Test
    @DisplayName("웨이팅이 있는데 오늘을 휴무로 저장하면 409이고 저장이 취소된다")
    void closingTodayWithWaitingRollsBack() {
        insertWaiting(today, "WAITING");

        assertErrorCode(() -> specialBusinessDayService.saveSpecialBusinessDay(storeId, today,
                new SpecialBusinessDayRequest(true, null, null, null, null)));

        assertThat(specialDayCount()).isZero();
    }

    @Test
    @DisplayName("웨이팅이 있는데 지금이 영업 밖이 되게 요일별 영업시간을 바꾸면 409이고 기존 설정이 유지된다")
    void replacingHoursOutsideNowRollsBack() {
        insertWaiting(today, "CALLED");

        assertErrorCode(() -> businessHourService.replaceBusinessHours(storeId,
                weeklyRequest(now.plusHours(2), now.plusHours(3))));

        assertThat(jdbc.queryForObject("SELECT open_time FROM business_hour WHERE store_id = ? LIMIT 1",
                LocalTime.class, storeId)).isEqualTo(now.minusHours(1));
    }

    @Test
    @DisplayName("웨이팅이 있어도 지금이 여전히 영업 중이면 영업시간을 바꿀 수 있다")
    void changeKeepingNowOpenIsSaved() {
        insertWaiting(today, "WAITING");

        businessHourService.replaceBusinessHours(storeId, weeklyRequest(now.minusHours(2), now.plusHours(2)));

        assertThat(jdbc.queryForObject("SELECT open_time FROM business_hour WHERE store_id = ? LIMIT 1",
                LocalTime.class, storeId)).isEqualTo(now.minusHours(2));
    }

    @Test
    @DisplayName("웨이팅이 없으면 오늘을 휴무로 저장할 수 있다")
    void closingTodayWithoutWaitingIsSaved() {
        specialBusinessDayService.saveSpecialBusinessDay(storeId, today,
                new SpecialBusinessDayRequest(true, null, null, null, null));

        assertThat(specialDayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 끝난 웨이팅은 검사 대상이 아니다")
    void endedWaitingIsIgnored() {
        insertWaiting(today, "CANCELED");

        specialBusinessDayService.saveSpecialBusinessDay(storeId, today,
                new SpecialBusinessDayRequest(true, null, null, null, null));

        assertThat(specialDayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘이 아닌 날짜의 웨이팅은 검사 대상이 아니다")
    void waitingOfAnotherDateIsIgnored() {
        insertWaiting(today.minusDays(1), "WAITING");

        specialBusinessDayService.saveSpecialBusinessDay(storeId, today,
                new SpecialBusinessDayRequest(true, null, null, null, null));

        assertThat(specialDayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘이 아닌 날짜의 특정일 설정은 오늘 웨이팅이 남아 있어도 저장된다")
    void specialDayOfAnotherDateIsSavedEvenIfClosedNow() {
        // 영업이 끝난 뒤(지금은 영업 밖)에도 처리되지 않은 웨이팅이 남아 있는 상황
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        insertWeeklyHours(now.plusHours(2), now.plusHours(3));
        insertWaiting(today, "WAITING");
        LocalDate nextWeek = today.plusDays(7);

        specialBusinessDayService.saveSpecialBusinessDay(storeId, nextWeek,
                new SpecialBusinessDayRequest(true, null, null, null, null));

        assertThat(specialDayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("특정일 설정을 삭제해 지금이 영업 밖이 되면 409이고 삭제가 취소된다")
    void deletingSpecialDayRollsBackWhenNowBecomesClosed() {
        // 요일별 설정은 나중 시간, 오늘만 특정일로 지금 영업 중
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        insertWeeklyHours(now.plusHours(2), now.plusHours(3));
        jdbc.update("""
                INSERT INTO special_business_day (store_id, business_date, closed, open_time, closing_time)
                VALUES (?, ?, false, ?, ?)
                """, storeId, today, now.minusHours(1), now.plusHours(1));
        insertWaiting(today, "WAITING");

        assertErrorCode(() -> specialBusinessDayService.deleteSpecialBusinessDay(storeId, today));

        assertThat(specialDayCount()).isEqualTo(1);
    }

    private BusinessHourUpdateRequest weeklyRequest(LocalTime open, LocalTime close) {
        List<BusinessHourRequest> hours = Arrays.stream(DayOfWeek.values())
                .map(day -> new BusinessHourRequest(day, open, close, null, null))
                .toList();
        return new BusinessHourUpdateRequest(hours);
    }

    private void insertWeeklyHours(LocalTime open, LocalTime close) {
        for (DayOfWeek day : DayOfWeek.values()) {
            jdbc.update("""
                    INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                    VALUES (?, ?, ?, ?)
                    """, storeId, day.getValue(), open, close);
        }
    }

    private void insertWaiting(LocalDate waitingDate, String status) {
        jdbc.update("""
                INSERT INTO waiting (
                    member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, 2, 1, 1, ?, now(), now())
                """, customerId, storeId, waitingDate, status);
    }

    private Long specialDayCount() {
        return jdbc.queryForObject("SELECT count(*) FROM special_business_day WHERE store_id = ?",
                Long.class, storeId);
    }

    private Long insertMember(String email, String userType) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'member', ?, ?)
                RETURNING id
                """, Long.class, email, "010-" + email.hashCode(), userType);
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_HOUR_HAS_WAITINGS));
    }
}
