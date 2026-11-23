package com.catchtable.waiting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 한산한 매장의 첫 신청은 대기열 Redis가 끊겨도 같은 요청 안에서 번호를 받는다.
@SpringBootTest(properties = {
        "waiting.dispatch.enabled=true",
        "waiting.dispatch.immediate=true",
        "waiting.dispatch.uri=redis://127.0.0.1:1/14"})
@AutoConfigureMockMvc
@DirtiesContext
@Import(DispatchFixture.TimeConfig.class)
class ImmediateDispatchOutageTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    DispatchFixture fixture;

    @BeforeEach
    void prepare() {
        fixture = new DispatchFixture(jdbc, mvc, json);
        fixture.create(1);
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void idleHeadIsRegisteredInTheSameRequestWhileRedisIsUnreachable() throws Exception {
        fixture.apply(fixture.members.getFirst(), status().isCreated());
    }
}
