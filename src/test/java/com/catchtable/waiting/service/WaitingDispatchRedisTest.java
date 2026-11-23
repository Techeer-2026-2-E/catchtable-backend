package com.catchtable.waiting.service;

import com.catchtable.global.redis.RedisEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 Redis 대기열(OFFER·ZPOPMIN)을 거쳐 워커가 접수 순서대로 식당 번호를 발급한다.
@SpringBootTest(properties = {
        "waiting.dispatch.enabled=true",
        "waiting.dispatch.immediate=false",
        "waiting.dispatch.uri=redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}/14"})
@AutoConfigureMockMvc
@DirtiesContext
@Import(DispatchFixture.TimeConfig.class)
class WaitingDispatchRedisTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("waitingRedis") RedisEndpoint redis;
    private DispatchFixture fixture;

    @BeforeEach
    void prepare() {
        redis.use(s -> s.commands().flushdb()); // 테스트 전용 DB 14
        fixture = new DispatchFixture(jdbc, mvc, json);
        fixture.create(3);
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void workerIssuesRestaurantNumbersThroughTheRedisQueueInIntakeOrder() throws Exception {
        List<String> receipts = new ArrayList<>();
        for (long member : fixture.members) {
            receipts.add(fixture.receiptId(fixture.apply(member, status().isAccepted())));
        }
        fixture.awaitNumbersInIntakeOrder(receipts);
        // OFFER 스크립트가 실제 Redis에서 순서 카운터를 올렸다.
        String order = redis.use(s -> s.commands().get("ct:waiting:{dispatch}:order"));
        assertThat(order).isNotNull();
    }
}
