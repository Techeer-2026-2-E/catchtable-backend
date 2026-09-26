package com.catchtable.store.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행. 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OwnerBusinessHourControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long storeId;

    @BeforeEach
    void setUp() {
        Long ownerId = jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('점주', '010-0000-0000', 'OWNER')
                RETURNING id""", Long.class);
        storeId = jdbcTemplate.queryForObject("""
                INSERT INTO store (owner_id, name, category, address,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, '테스트 매장', 'KOREAN', '서울', 90, 30, 10)
                RETURNING id""", Long.class, ownerId);
    }

    private ResultActions putHours(Long storeId, String body) throws Exception {
        return mockMvc.perform(put("/api/owner/stores/{storeId}/business-hours", storeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("일주일치를 저장하고 요일 순으로 조회한다")
    void replaceAndGet() throws Exception {
        putHours(storeId, """
                {"businessHours": [
                  {"dayOfWeek": "FRIDAY", "openTime": "18:00", "closingTime": "02:00"},
                  {"dayOfWeek": "MONDAY", "openTime": "11:00", "closingTime": "22:00",
                   "breakStartTime": "15:00", "breakEndTime": "16:00"}
                ]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/api/owner/stores/{storeId}/business-hours", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$[0].breakStartTime").value("15:00:00"))
                .andExpect(jsonPath("$[1].dayOfWeek").value("FRIDAY"))
                .andExpect(jsonPath("$[1].closingTime").value("02:00:00"))
                .andExpect(jsonPath("$[1].breakStartTime").doesNotExist());
    }

    @Test
    @DisplayName("빠진 요일은 휴무로 바뀌고, 다시 넣으면 새로 등록된다 (소프트 삭제와 유니크 충돌 없음)")
    void removeThenAddDayAgain() throws Exception {
        putHours(storeId, """
                {"businessHours": [
                  {"dayOfWeek": "MONDAY", "openTime": "11:00", "closingTime": "22:00"},
                  {"dayOfWeek": "TUESDAY", "openTime": "11:00", "closingTime": "22:00"}
                ]}""").andExpect(status().isOk());

        putHours(storeId, """
                {"businessHours": [
                  {"dayOfWeek": "TUESDAY", "openTime": "12:00", "closingTime": "23:00"}
                ]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].openTime").value("12:00:00"));

        putHours(storeId, """
                {"businessHours": [
                  {"dayOfWeek": "MONDAY", "openTime": "10:00", "closingTime": "20:00"},
                  {"dayOfWeek": "TUESDAY", "openTime": "12:00", "closingTime": "23:00"}
                ]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].openTime").value("10:00:00"));
    }

    @Test
    @DisplayName("빈 목록이면 전체 휴무")
    void emptyMeansAllClosed() throws Exception {
        putHours(storeId, """
                {"businessHours": [{"dayOfWeek": "MONDAY", "openTime": "11:00", "closingTime": "22:00"}]}""")
                .andExpect(status().isOk());

        putHours(storeId, """
                {"businessHours": []}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("같은 요일이 두 번 오면 400")
    void duplicateDay() throws Exception {
        putHours(storeId, """
                {"businessHours": [
                  {"dayOfWeek": "MONDAY", "openTime": "11:00", "closingTime": "22:00"},
                  {"dayOfWeek": "MONDAY", "openTime": "12:00", "closingTime": "20:00"}
                ]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_DAY"));
    }

    @Test
    @DisplayName("브레이크가 영업시간 밖이면 400")
    void invalidBreak() throws Exception {
        putHours(storeId, """
                {"businessHours": [
                  {"dayOfWeek": "MONDAY", "openTime": "11:00", "closingTime": "22:00",
                   "breakStartTime": "21:00", "breakEndTime": "23:00"}
                ]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BREAK_TIME"));
    }

    @Test
    @DisplayName("필수 값이 없으면 400")
    void missingField() throws Exception {
        putHours(storeId, """
                {"businessHours": [{"dayOfWeek": "MONDAY", "openTime": "11:00"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("없는 매장이면 404")
    void storeNotFound() throws Exception {
        mockMvc.perform(get("/api/owner/stores/{storeId}/business-hours", -1L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }
}
