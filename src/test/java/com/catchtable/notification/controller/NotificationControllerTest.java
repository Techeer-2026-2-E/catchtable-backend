package com.catchtable.notification.controller;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.waiting.dto.WaitingCallNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

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

    @Autowired
    private ApplicationEventPublisher publisher;

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

    private MvcResult openConnection() throws Exception {
        return mockMvc.perform(get(SUBSCRIBE_URL).param("memberId", String.valueOf(MEMBER_ID)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    @DisplayName("입장 호출 알림은 문구와 호출 정보(payload)를 함께 보낸다")
    void waitingCalled() throws Exception {
        MvcResult result = openConnection();

        // 트랜잭션 밖 발행은 리스너가 바로 보낸다 (fallbackExecution)
        publisher.publishEvent(WaitingCallNotification.of(MEMBER_ID, 30L, 12, "스케줄 청담",
                OffsetDateTime.parse("2026-10-11T18:00:00+09:00"),
                OffsetDateTime.parse("2026-10-11T18:10:00+09:00")));

        // MockMvc 기본 문자셋(ISO-8859-1)으로 읽으면 한글이 깨지므로 UTF-8로 읽는다
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:WAITING_CALLED");
        assertThat(body).contains("\"targetId\":30");
        assertThat(body).contains("\"message\":\"12번 고객님, 18:10까지 스케줄 청담에 입장해 주세요.\"");
        assertThat(body).contains(
                "\"status\":\"CALLED\"",
                "\"waitNumber\":12",
                "\"storeName\":\"스케줄 청담\"",
                "\"calledAt\":\"2026-10-11T18:00:00+09:00\"",
                "\"expiresAt\":\"2026-10-11T18:10:00+09:00\"");
    }

    @Test
    @DisplayName("호출 알림은 같은 회원의 열린 연결마다 한 번씩 보낸다")
    void waitingCalledOncePerConnection() throws Exception {
        MvcResult first = openConnection();
        MvcResult second = openConnection();

        publisher.publishEvent(WaitingCallNotification.of(MEMBER_ID, 30L, 12, "스케줄 청담",
                OffsetDateTime.parse("2026-10-11T18:00:00+09:00"),
                OffsetDateTime.parse("2026-10-11T18:10:00+09:00")));

        assertThat(first.getResponse().getContentAsString()).containsOnlyOnce("event:WAITING_CALLED");
        assertThat(second.getResponse().getContentAsString()).containsOnlyOnce("event:WAITING_CALLED");
    }

    @Test
    @DisplayName("부가 데이터가 없는 알림은 payload가 null이다")
    void payloadNullWithoutExtraData() throws Exception {
        MvcResult result = openConnection();

        publisher.publishEvent(new CustomerNotificationEvent(
                MEMBER_ID, NotificationType.RESERVATION_CANCELED, 10L, "예약이 취소되었습니다"));

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:RESERVATION_CANCELED");
        assertThat(body).contains("\"payload\":null");
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
