package com.catchtable.notification.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserNotificationSettingControllerTest {

    private static final String PATH = "/api/user/members/me/notification-settings";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private Long customerId;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        customerId = insertMember("고객", "CUSTOMER");
        ownerId = insertMember("점주", "OWNER");
    }

    @Test
    @DisplayName("설정 전 조회는 200과 기본값이다")
    void getDefaults() throws Exception {
        mockMvc.perform(get(PATH).header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationEnabled").value(true))
                .andExpect(jsonPath("$.waitingEnabled").value(true));
    }

    @Test
    @DisplayName("변경은 200과 변경 결과를 돌려주고 조회에 반영된다")
    void updateThenGet() throws Exception {
        putSettings(customerId, "{\"reservationEnabled\":false,\"waitingEnabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationEnabled").value(false))
                .andExpect(jsonPath("$.waitingEnabled").value(true));

        mockMvc.perform(get(PATH).header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationEnabled").value(false))
                .andExpect(jsonPath("$.waitingEnabled").value(true));
    }

    @Test
    @DisplayName("헤더가 없으면 401이다")
    void requiresHeader() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("점주는 403이다")
    void ownerForbidden() throws Exception {
        mockMvc.perform(get(PATH).header("X-Member-Id", ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
        putSettings(ownerId, "{\"reservationEnabled\":false,\"waitingEnabled\":false}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
    }

    @Test
    @DisplayName("본문 항목이 빠지면 400이다")
    void requiresBothFields() throws Exception {
        putSettings(customerId, "{\"reservationEnabled\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        putSettings(customerId, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private ResultActions putSettings(Long memberId, String body) throws Exception {
        return mockMvc.perform(put(PATH).header("X-Member-Id", memberId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Long insertMember(String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES (?, '010-0000-0000', ?)
                RETURNING id""", Long.class, name, type);
    }
}
