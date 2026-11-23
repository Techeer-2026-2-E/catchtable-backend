package com.catchtable.waiting.service;

import com.catchtable.global.redis.RedisEndpoint;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 Redis 대기열(OFFER·ZPOPMIN)을 거쳐 워커가 접수 순서대로 식당 번호를 발급한다.
@SpringBootTest(properties = {
        "waiting.dispatch.enabled=true",
        "waiting.dispatch.immediate=false",
        "waiting.dispatch.uri=redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}/14"})
@AutoConfigureMockMvc
@DirtiesContext
class WaitingDispatchRedisTest {
    private static final Instant AFTERNOON = Instant.parse("2031-02-01T05:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(AFTERNOON, SEOUL);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("waitingRedis") RedisEndpoint redis;
    private final List<Long> members = new ArrayList<>();
    private long store;

    @BeforeEach
    void prepare() {
        redis.use(s -> s.commands().flushdb()); // 테스트 전용 DB 14
        for (int i = 0; i < 3; i++) {
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

    @AfterEach
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

    @Test
    void workerIssuesRestaurantNumbersThroughTheRedisQueueInIntakeOrder() throws Exception {
        List<String> receipts = new ArrayList<>();
        for (long member : members) {
            var body = mvc.perform(post("/api/user/waitings").header("X-Member-Id", member)
                            .header("Idempotency-Key", "dispatch-" + member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeId\":" + store + ",\"partyCount\":2}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            receipts.add(json.readTree(body).get("receiptId").asText());
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (int i = 0; i < receipts.size(); i++) {
                var view = json.readTree(mvc.perform(get("/api/user/waitings/receipts/{id}", receipts.get(i))
                        .header("X-Member-Id", members.get(i))).andReturn().getResponse().getContentAsString());
                assertThat(view.get("status").asText()).isEqualTo("REGISTERED");
                assertThat(view.get("waitNumber").asInt()).isEqualTo(i + 1);
            }
        });
        // OFFER 스크립트가 실제 Redis에서 순서 카운터를 올렸다.
        String order = redis.use(s -> s.commands().get("ct:waiting:{dispatch}:order"));
        assertThat(order).isNotNull();
    }
}
