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

import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행. 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OwnerSpecialBusinessDayControllerTest {

    private static final String PATH = "/api/owner/stores/{storeId}/special-business-days";
    private static final String DATE_PATH = PATH + "/{businessDate}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long storeId;
    private LocalDate date;

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
        date = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(7);
    }

    private ResultActions putDay(Object businessDate, String body) throws Exception {
        return mockMvc.perform(put(DATE_PATH, storeId, businessDate)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("휴무를 저장하고 조회한다")
    void saveClosedAndGet() throws Exception {
        putDay(date, """
                {"closed": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value(date.toString()))
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.openTime").doesNotExist());

        mockMvc.perform(get(PATH, storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessDate").value(date.toString()));
    }

    @Test
    @DisplayName("같은 날짜를 다시 보내면 예외 영업으로 수정되고, 목록은 날짜순이다")
    void updateAndSortByDate() throws Exception {
        putDay(date.plusDays(1), """
                {"closed": true}""").andExpect(status().isOk());
        putDay(date, """
                {"closed": true}""").andExpect(status().isOk());

        putDay(date, """
                {"closed": false, "openTime": "10:00", "closingTime": "20:00",
                 "breakStartTime": "15:00", "breakEndTime": "16:00"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.openTime").value("10:00:00"))
                .andExpect(jsonPath("$.breakEndTime").value("16:00:00"));

        mockMvc.perform(get(PATH, storeId))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].businessDate").value(date.toString()))
                .andExpect(jsonPath("$[0].closed").value(false))
                .andExpect(jsonPath("$[1].businessDate").value(date.plusDays(1).toString()));
    }

    @Test
    @DisplayName("삭제하면 204이고 목록에서 사라지며, 다시 삭제하면 404")
    void deleteDay() throws Exception {
        putDay(date, """
                {"closed": true}""").andExpect(status().isOk());

        mockMvc.perform(delete(DATE_PATH, storeId, date)).andExpect(status().isNoContent());

        mockMvc.perform(get(PATH, storeId)).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(delete(DATE_PATH, storeId, date)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("closed가 없으면 400")
    void missingClosed() throws Exception {
        putDay(date, """
                {"openTime": "10:00", "closingTime": "20:00"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("휴무인데 시각이 함께 오면 400이고 저장되지 않는다")
    void closedDayWithTimes() throws Exception {
        putDay(date, """
                {"closed": true, "openTime": "10:00", "closingTime": "20:00"}""")
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(PATH, storeId)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("영업일인데 시각이 없으면 400")
    void openDayWithoutTimes() throws Exception {
        putDay(date, """
                {"closed": false}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("날짜 형식이 잘못되면 400")
    void invalidDate() throws Exception {
        putDay("2026-13-99", """
                {"closed": true}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지난 날짜는 400")
    void pastDate() throws Exception {
        putDay(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1), """
                {"closed": true}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 매장은 404")
    void unknownStore() throws Exception {
        mockMvc.perform(get(PATH, -1L)).andExpect(status().isNotFound());
        mockMvc.perform(put(DATE_PATH, -1L, date)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closed": true}"""))
                .andExpect(status().isNotFound());
    }
}
