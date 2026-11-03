package com.catchtable.waiting.controller;

import com.catchtable.waiting.WaitingDeferTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WaitingDeferTestContext
class WaitingDeferControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 11);
    private static final String PATH = "/api/owner/waitings/{waitingId}/defer";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private String suffix;
    private Long ownerId;
    private Long storeId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString();
        ownerId = insertMember("owner", "OWNER");
        storeId = jdbc.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, ?, 'KOREAN', 'Seoul', true, 90, 30, 10)
                RETURNING id
                """, Long.class, ownerId, "defer-" + suffix);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from waiting_defer_history where waiting_id in (select id from waiting where store_id = ?)",
                storeId);
        jdbc.update("delete from waiting where store_id = ?", storeId);
        jdbc.update("delete from store where id = ?", storeId);
        jdbc.update("delete from member where email like ?", "%-" + suffix + "@example.com");
    }

    @Test
    @DisplayName("연기하면 200과 변경 전후 순번을 돌려준다")
    void deferReturnsPositions() throws Exception {
        Long first = insertWaiting(1);
        insertWaiting(2);

        mockMvc.perform(post(PATH, first).header("X-Member-Id", ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(first))
                .andExpect(jsonPath("$.waitNumber").value(1))
                .andExpect(jsonPath("$.deferCount").value(1))
                .andExpect(jsonPath("$.beforePosition").value(1))
                .andExpect(jsonPath("$.afterPosition").value(2));
    }

    @Test
    @DisplayName("연기할 수 없으면 409와 에러 코드를 돌려준다")
    void deferConflictReturnsCode() throws Exception {
        Long first = insertWaiting(1);
        insertWaiting(2);
        insertWaiting(3);

        mockMvc.perform(post(PATH, insertWaiting(4)).header("X-Member-Id", ownerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_DEFERRABLE"));
        mockMvc.perform(post(PATH, first).header("X-Member-Id", ownerId)).andExpect(status().isOk());
        mockMvc.perform(post(PATH, first).header("X-Member-Id", ownerId)).andExpect(status().isOk());
        mockMvc.perform(post(PATH, first).header("X-Member-Id", ownerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_DEFER_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("남의 매장 웨이팅은 404, 점주가 아니면 403이다")
    void deferChecksOwnerAndOwnership() throws Exception {
        Long first = insertWaiting(1);
        insertWaiting(2);
        Long otherOwner = insertMember("other", "OWNER");
        Long customer = insertMember("customer", "CUSTOMER");

        mockMvc.perform(post(PATH, first).header("X-Member-Id", otherOwner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
        mockMvc.perform(post(PATH, -1L).header("X-Member-Id", ownerId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(PATH, first).header("X-Member-Id", customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
    }

    private Long insertWaiting(int number) {
        Long customerId = insertMember("customer" + number, "CUSTOMER");
        return jdbc.queryForObject("""
                INSERT INTO waiting (
                    member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, 2, ?, ?, 'WAITING', now(), now())
                RETURNING id
                """, Long.class, customerId, storeId, TODAY, number, number);
    }

    private Long insertMember(String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'test', '010-0000-0000', ?)
                RETURNING id
                """, Long.class, name + "-" + suffix + "@example.com", type);
    }
}
