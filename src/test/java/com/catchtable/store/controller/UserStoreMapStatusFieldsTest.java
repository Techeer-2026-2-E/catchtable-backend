package com.catchtable.store.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 지도 목록(반경·사각형)의 영업 상태·별점 필드. 시계를 2026-11-09(월) 11:30 KST로 고정한다.
// 기준점은 부산시청이고 위도 0.001도 = 약 111m 간격으로 매장을 놓아 가까운 순서를 고정한다
// 시계를 바꾸면 스프링 컨텍스트가 하나 더 생겨 DB 연결(기본 한도 100)이 모자라 다른 테스트가 실패한다.
// 그래서 이 클래스가 끝나면 컨텍스트를 닫아 연결을 돌려준다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserStoreMapStatusFieldsTest {

    private static final double LAT = 35.1796;
    private static final double LNG = 129.0756;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final String RADIUS = "/api/user/stores/map/radius?lat=" + LAT + "&lng=" + LNG;
    private static final String BOUNDS = "/api/user/stores/map/bounds?lat=" + LAT + "&lng=" + LNG
            + "&swLat=" + (LAT - 0.01) + "&swLng=" + (LNG - 0.01)
            + "&neLat=" + (LAT + 0.01) + "&neLng=" + (LNG + 0.01);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedMapClock() {
            return Clock.fixed(Instant.parse("2026-11-09T02:30:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;
    private int reviewSlot;

    @BeforeEach
    void setUp() {
        memberId = jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('손님', '010-1111-1111', 'CUSTOMER')
                RETURNING id
                """, Long.class);

        long open = insertStore("영업 중", 0.0010);
        weeklyHours(open, "11:00", "22:00", null, null);
        review(open, 5);
        review(open, 4);
        review(open, 4);
        deletedReview(open, 1);

        long onBreak = insertStore("브레이크", 0.0020);
        weeklyHours(onBreak, "10:00", "22:00", "11:00", "12:00");
        review(onBreak, 5);
        review(onBreak, 4);

        long beforeOpen = insertStore("영업 전", 0.0030);
        weeklyHours(beforeOpen, "18:00", "22:00", null, null);
        review(beforeOpen, 3);
        review(beforeOpen, 4);
        review(beforeOpen, 4);

        long closedAfterHours = insertStore("마감", 0.0040);
        weeklyHours(closedAfterHours, "06:00", "10:00", null, null);

        long specialClosed = insertStore("특정일 휴무", 0.0050);
        weeklyHours(specialClosed, "11:00", "22:00", null, null);
        jdbcTemplate.update("INSERT INTO special_business_day (store_id, business_date, closed) VALUES (?, ?, TRUE)",
                specialClosed, TODAY);

        insertStore("영업시간 없음", 0.0060);

        long specialOpen = insertStore("특정일 예외 영업", 0.0070);
        weeklyHours(specialOpen, "06:00", "10:00", null, null);
        jdbcTemplate.update("""
                INSERT INTO special_business_day (store_id, business_date, closed, open_time, closing_time)
                VALUES (?, ?, FALSE, '11:00', '23:00')
                """, specialOpen, TODAY);
    }

    @Test
    @DisplayName("반경 목록에 영업 상태와 다음 변경 시각을 내려준다")
    void radiusIncludesOpenStatus() throws Exception {
        assertOpenStatuses(mockMvc.perform(get(RADIUS)));
    }

    @Test
    @DisplayName("사각형 목록에 영업 상태와 다음 변경 시각을 내려준다")
    void boundsIncludesOpenStatus() throws Exception {
        assertOpenStatuses(mockMvc.perform(get(BOUNDS)));
    }

    @Test
    @DisplayName("반경 목록에 평균 별점(소수 첫째 자리)과 리뷰 수를 내려주고 삭제된 리뷰는 뺀다")
    void radiusIncludesRating() throws Exception {
        assertRatings(mockMvc.perform(get(RADIUS)));
    }

    @Test
    @DisplayName("사각형 목록에 평균 별점(소수 첫째 자리)과 리뷰 수를 내려주고 삭제된 리뷰는 뺀다")
    void boundsIncludesRating() throws Exception {
        assertRatings(mockMvc.perform(get(BOUNDS)));
    }

    @Test
    @DisplayName("페이지를 나눠도 각 페이지 매장에 영업 상태와 별점이 채워진다")
    void pagesAreEnriched() throws Exception {
        mockMvc.perform(get(RADIUS + "&size=2&page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext", is(true)))
                .andExpect(jsonPath("$.stores[0].name", is("영업 전")))
                .andExpect(jsonPath("$.stores[0].openStatus", is("BEFORE_OPEN")))
                .andExpect(jsonPath("$.stores[0].ratingAverage", is(3.7)))
                .andExpect(jsonPath("$.stores[1].name", is("마감")))
                .andExpect(jsonPath("$.stores[1].openStatus", is("CLOSED")));
    }

    @Test
    @DisplayName("결과가 없는 지역도 정상 응답한다")
    void emptyResultIsOk() throws Exception {
        mockMvc.perform(get("/api/user/stores/map/radius?lat=33.5&lng=126.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores").isEmpty());
    }

    private void assertOpenStatuses(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].name", is("영업 중")))
                .andExpect(jsonPath("$.stores[0].openStatus", is("OPEN")))
                .andExpect(jsonPath("$.stores[0].nextChangeAt", is("2026-11-09T22:00:00")))
                .andExpect(jsonPath("$.stores[1].name", is("브레이크")))
                .andExpect(jsonPath("$.stores[1].openStatus", is("BREAK")))
                .andExpect(jsonPath("$.stores[1].nextChangeAt", is("2026-11-09T12:00:00")))
                .andExpect(jsonPath("$.stores[2].name", is("영업 전")))
                .andExpect(jsonPath("$.stores[2].openStatus", is("BEFORE_OPEN")))
                .andExpect(jsonPath("$.stores[2].nextChangeAt", is("2026-11-09T18:00:00")))
                .andExpect(jsonPath("$.stores[3].name", is("마감")))
                .andExpect(jsonPath("$.stores[3].openStatus", is("CLOSED")))
                .andExpect(jsonPath("$.stores[3].nextChangeAt", is("2026-11-10T06:00:00")))
                .andExpect(jsonPath("$.stores[4].name", is("특정일 휴무")))
                .andExpect(jsonPath("$.stores[4].openStatus", is("CLOSED")))
                .andExpect(jsonPath("$.stores[4].nextChangeAt", is("2026-11-10T11:00:00")))
                .andExpect(jsonPath("$.stores[5].name", is("영업시간 없음")))
                .andExpect(jsonPath("$.stores[5].openStatus").value(nullValue()))
                .andExpect(jsonPath("$.stores[5].nextChangeAt").value(nullValue()))
                .andExpect(jsonPath("$.stores[6].name", is("특정일 예외 영업")))
                .andExpect(jsonPath("$.stores[6].openStatus", is("OPEN")))
                .andExpect(jsonPath("$.stores[6].nextChangeAt", is("2026-11-09T23:00:00")));
    }

    private void assertRatings(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].ratingAverage", is(4.3)))     // (5+4+4)/3 = 4.33.., 삭제된 1점은 제외
                .andExpect(jsonPath("$.stores[0].reviewCount", is(3)))
                .andExpect(jsonPath("$.stores[1].ratingAverage", is(4.5)))
                .andExpect(jsonPath("$.stores[1].reviewCount", is(2)))
                .andExpect(jsonPath("$.stores[2].ratingAverage", is(3.7)))     // (3+4+4)/3 = 3.66..
                .andExpect(jsonPath("$.stores[2].reviewCount", is(3)))
                .andExpect(jsonPath("$.stores[3].ratingAverage").value(nullValue()))
                .andExpect(jsonPath("$.stores[3].reviewCount", is(0)));
    }

    private long insertStore(String name, double latOffset) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO store (name, category, address, latitude, longitude,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', '부산 테스트 주소', ?, ?, 120, 30, 10)
                RETURNING id
                """, Long.class, name, LAT + latOffset, LNG);
    }

    // 월~일 같은 시간으로 영업
    private void weeklyHours(long storeId, String open, String closing, String breakStart, String breakEnd) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update("""
                    INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time,
                                               break_start_time, break_end_time)
                    VALUES (?, ?, ?::time, ?::time, ?::time, ?::time)
                    """, storeId, day, open, closing, breakStart, breakEnd);
        }
    }

    private void review(long storeId, int rating) {
        insertReview(storeId, rating, false);
    }

    private void deletedReview(long storeId, int rating) {
        insertReview(storeId, rating, true);
    }

    // 리뷰는 완료된 예약에 달린다. 같은 테이블의 예약 시간이 겹치지 않도록 슬롯마다 3시간씩 띄운다
    private void insertReview(long storeId, int rating, boolean deleted) {
        Long tableId = jdbcTemplate.query("SELECT id FROM store_table WHERE store_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, storeId);
        if (tableId == null) {
            tableId = jdbcTemplate.queryForObject("""
                    INSERT INTO store_table (store_id, table_number, capacity, status)
                    VALUES (?, 1, 4, 'ACTIVE') RETURNING id
                    """, Long.class, storeId);
        }
        OffsetDateTime start = OffsetDateTime.parse("2026-10-01T10:00:00+09:00").plusHours(3L * reviewSlot++);
        Long reservationId = jdbcTemplate.queryForObject("""
                INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at, reservation_end_at,
                                         party_size, status, requested_at, confirmed_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 2, 'COMPLETED', ?, ?, ?)
                RETURNING id
                """, Long.class, memberId, storeId, tableId, start, start.plusHours(2),
                start.minusDays(1), start.minusDays(1), start.plusHours(2));
        jdbcTemplate.update("""
                INSERT INTO review (reservation_id, rating, content, deleted_at)
                VALUES (?, ?, '리뷰', ?)
                """, reservationId, rating, deleted ? start.plusDays(1) : null);
    }
}
