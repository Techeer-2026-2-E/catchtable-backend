package com.catchtable.store.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행. 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserStoreControllerTest {

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
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, '스케줄 청담', 'WESTERN', '서울 강남구', TRUE, 120, 30, 10)
                RETURNING id""", Long.class, ownerId);
    }

    private void insertHour(int dayOfWeek, String open, String close, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time, deleted_at)
                VALUES (?, ?, ?::time, ?::time, CASE WHEN ? THEN now() END)""",
                storeId, dayOfWeek, open, close, deleted);
    }

    private void insertMenu(String name, int price, String description) {
        jdbcTemplate.update("INSERT INTO menu (store_id, name, price, description) VALUES (?, ?, ?, ?)",
                storeId, name, price, description);
    }

    @Test
    @DisplayName("매장 기본 정보와 영업시간을 요일 순으로 조회한다")
    void getStoreDetail() throws Exception {
        insertHour(7, "11:00", "05:00", false);   // 일요일, 다음 날 새벽 마감
        insertHour(1, "11:00", "22:00", false);   // 월요일

        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(storeId))
                .andExpect(jsonPath("$.name").value("스케줄 청담"))
                .andExpect(jsonPath("$.category").value("WESTERN"))
                .andExpect(jsonPath("$.categoryName").value("양식"))
                .andExpect(jsonPath("$.address").value("서울 강남구"))
                .andExpect(jsonPath("$.waitingAvailable").value(true))
                .andExpect(jsonPath("$.businessHours", hasSize(2)))
                .andExpect(jsonPath("$.businessHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.businessHours[1].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.businessHours[1].closingTime").value("05:00:00"));
    }

    @Test
    @DisplayName("소프트 삭제된 요일(휴무)은 영업시간에 나오지 않는다")
    void excludeDeletedHours() throws Exception {
        insertHour(1, "11:00", "22:00", false);
        insertHour(2, "11:00", "22:00", true);

        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessHours", hasSize(1)))
                .andExpect(jsonPath("$.businessHours[0].dayOfWeek").value("MONDAY"));
    }

    @Test
    @DisplayName("메뉴를 등록순으로 함께 조회한다")
    void includeMenus() throws Exception {
        insertMenu("런치 코스", 45000, "점심 한정");
        insertMenu("콜키지", 0, null);

        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(2)))
                .andExpect(jsonPath("$.menus[0].name").value("런치 코스"))
                .andExpect(jsonPath("$.menus[0].price").value(45000))
                .andExpect(jsonPath("$.menus[0].description").value("점심 한정"))
                .andExpect(jsonPath("$.menus[1].name").value("콜키지"))
                .andExpect(jsonPath("$.menus[1].price").value(0));
    }

    @Test
    @DisplayName("메뉴가 없으면 빈 배열")
    void emptyMenus() throws Exception {
        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(0)));
    }

    @Test
    @DisplayName("없는 매장이면 404")
    void storeNotFound() throws Exception {
        mockMvc.perform(get("/api/user/stores/{storeId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }
}
