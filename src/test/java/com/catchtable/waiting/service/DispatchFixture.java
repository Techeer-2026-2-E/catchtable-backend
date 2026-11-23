package com.catchtable.waiting.service;

import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 워커가 켜진 컨텍스트에서 장부 매장을 만들고, 접수와 번호 발급을 확인하는 공용 도구
final class DispatchFixture {
    static final Instant AFTERNOON = Instant.parse("2031-02-01T05:00:00Z");
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(AFTERNOON, SEOUL);
        }
    }

    private final JdbcTemplate jdbc;
    private final MockMvc mvc;
    private final ObjectMapper json;
    final List<Long> members = new ArrayList<>();
    long store;

    DispatchFixture(JdbcTemplate jdbc, MockMvc mvc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.mvc = mvc;
        this.json = json;
    }

    void create(int applicants) {
        for (int i = 0; i < applicants; i++) {
            members.add(jdbc.queryForObject("""
                    INSERT INTO member(name,phone,user_type) VALUES ('dispatch','00000000000','CUSTOMER') RETURNING id
                    """, Long.class));
        }
        store = jdbc.queryForObject("""
                INSERT INTO store(owner_id,name,category,address,waiting_available,
                    reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
                VALUES (?, 'dispatch', 'KOREAN','dispatch',true,90,30,10) RETURNING id
                """, Long.class, members.getFirst());
        WaitingBusinessHourFixture.saveAllDays(jdbc, store, LocalTime.of(12, 0), LocalTime.of(22, 0));
        jdbc.update("INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (?,?,10)",
                store, LocalDate.ofInstant(AFTERNOON, SEOUL));
    }

    void clean() {
        for (long member : members) {
            jdbc.update("DELETE FROM waiting_receipt_cancel WHERE member_id=?", member);
            jdbc.update("DELETE FROM waiting_receipt_key WHERE member_id=?", member);
        }
        jdbc.update("DELETE FROM waiting_receipt WHERE store_id=?", store);
        jdbc.update("DELETE FROM waiting_intake_day WHERE store_id=?", store);
        jdbc.update("DELETE FROM waiting WHERE store_id=?", store);
        WaitingBusinessHourFixture.clear(jdbc, store);
        jdbc.update("DELETE FROM store WHERE id=?", store);
        for (long member : members) jdbc.update("DELETE FROM member WHERE id=?", member);
        members.clear();
    }

    /** Applies once and returns the response body. */
    String apply(long member, ResultMatcher expected) throws Exception {
        return mvc.perform(post("/api/user/waitings").header("X-Member-Id", member)
                        .header("Idempotency-Key", "dispatch-" + member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + store + ",\"partyCount\":2}"))
                .andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    String receiptId(String body) throws Exception {
        return json.readTree(body).get("receiptId").asText();
    }

    void awaitNumbersInIntakeOrder(List<String> receipts) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (int i = 0; i < receipts.size(); i++) {
                var view = json.readTree(mvc.perform(get("/api/user/waitings/receipts/{id}", receipts.get(i))
                        .header("X-Member-Id", members.get(i))).andReturn().getResponse().getContentAsString());
                assertThat(view.get("status").asText()).isEqualTo("REGISTERED");
                assertThat(view.get("waitNumber").asInt()).isEqualTo(i + 1);
            }
        });
    }
}
