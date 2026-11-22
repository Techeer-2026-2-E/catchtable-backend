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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserNotificationInboxControllerTest {

    private static final String PATH = "/api/user/notifications";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private Long memberId;
    private Long olderId;
    private Long newerId;

    @BeforeEach
    void setUp() {
        memberId = jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('고객', '010-0000-0000', 'CUSTOMER')
                RETURNING id""", Long.class);
        olderId = insertNotification("WAITING_CALLED", "입장해 주세요");
        newerId = insertNotification("RESERVATION_CONFIRMED", "예약이 확정되었습니다");
    }

    @Test
    @DisplayName("목록은 200이고 최신순 알림과 다음 커서를 돌려준다")
    void getInbox() throws Exception {
        mockMvc.perform(get(PATH).param("size", "1").header("X-Member-Id", memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].id").value(newerId))
                .andExpect(jsonPath("$.notifications[0].type").value("RESERVATION_CONFIRMED"))
                .andExpect(jsonPath("$.notifications[0].message").value("예약이 확정되었습니다"))
                .andExpect(jsonPath("$.notifications[0].read").value(false))
                .andExpect(jsonPath("$.notifications[0].createdAt").exists())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextLastId").value(newerId));

        mockMvc.perform(get(PATH).param("lastId", newerId.toString()).header("X-Member-Id", memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications[0].id").value(olderId))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextLastId").doesNotExist());
    }

    @Test
    @DisplayName("lastId까지 읽음 처리하면 204이고 안 읽음 여부에 반영된다")
    void markReadThenUnread() throws Exception {
        mockMvc.perform(get(PATH + "/unread").header("X-Member-Id", memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasUnread").value(true));

        markRead("{\"lastId\":" + newerId + "}").andExpect(status().isNoContent());

        mockMvc.perform(get(PATH + "/unread").header("X-Member-Id", memberId))
                .andExpect(jsonPath("$.hasUnread").value(false));
    }

    @Test
    @DisplayName("lastId가 없거나 0 이하이면 400이다")
    void markReadValidation() throws Exception {
        markRead("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        markRead("{\"lastId\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("삭제는 204이고 다시 지우면 404다")
    void deleteThenNotFound() throws Exception {
        mockMvc.perform(delete(PATH + "/" + newerId).header("X-Member-Id", memberId))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(PATH + "/" + newerId).header("X-Member-Id", memberId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));

        mockMvc.perform(get(PATH).header("X-Member-Id", memberId))
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].id").value(olderId));
    }

    @Test
    @DisplayName("헤더가 없으면 네 API 모두 401이다")
    void requiresHeader() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH + "/unread")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PATH + "/read").contentType(MediaType.APPLICATION_JSON).content("{\"lastId\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PATH + "/" + newerId)).andExpect(status().isUnauthorized());
    }

    private ResultActions markRead(String body) throws Exception {
        return mockMvc.perform(patch(PATH + "/read")
                .header("X-Member-Id", memberId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Long insertNotification(String type, String message) {
        return jdbc.queryForObject("""
                INSERT INTO notification (member_id, target_id, type, message, event_key, created_at)
                VALUES (?, 1, ?, ?, ?, now())
                RETURNING id""", Long.class, memberId, type, message, "test-" + type);
    }
}
