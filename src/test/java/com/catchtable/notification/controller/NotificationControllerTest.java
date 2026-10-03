package com.catchtable.notification.controller;

import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행
@SpringBootTest
@AutoConfigureMockMvc
class NotificationControllerTest {

    private static final String SUBSCRIBE_URL = "/api/user/notifications/subscribe";
    private static final Long MEMBER_ID = 900_101L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseEmitterRepository repository;

    @AfterEach
    void tearDown() {
        repository.findAllByMemberId(MEMBER_ID).forEach(emitter -> repository.delete(MEMBER_ID, emitter));
    }

    @Test
    @DisplayName("구독하면 SSE 연결이 열리고 connect 이벤트를 받는다")
    void subscribe() throws Exception {
        MvcResult result = mockMvc.perform(get(SUBSCRIBE_URL).param("memberId", String.valueOf(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getContentAsString()).contains("event:connect");
        assertThat(repository.findAllByMemberId(MEMBER_ID)).hasSize(1);
    }

    @Test
    @DisplayName("memberId가 없으면 400")
    void missingMemberId() throws Exception {
        mockMvc.perform(get(SUBSCRIBE_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("memberId가 숫자가 아니면 400")
    void invalidMemberId() throws Exception {
        mockMvc.perform(get(SUBSCRIBE_URL).param("memberId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
