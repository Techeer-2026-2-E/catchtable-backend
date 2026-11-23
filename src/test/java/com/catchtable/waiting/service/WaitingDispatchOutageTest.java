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

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 대기열 Redis에 연결할 수 없어도 워커는 PG 장부에서 미완료 매장을 읽어 번호를 발급한다.
@SpringBootTest(properties = {
        "waiting.dispatch.enabled=true",
        "waiting.dispatch.immediate=false",
        "waiting.dispatch.uri=redis://127.0.0.1:1/14"})
@AutoConfigureMockMvc
@DirtiesContext
@Import(DispatchFixture.TimeConfig.class)
class WaitingDispatchOutageTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    DispatchFixture fixture;

    @BeforeEach
    void prepare() {
        fixture = new DispatchFixture(jdbc, mvc, json);
        fixture.create(3);
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void workerIssuesNumbersFromTheLedgerWhileRedisIsUnreachable() throws Exception {
        List<String> receipts = new ArrayList<>();
        for (long member : fixture.members) {
            receipts.add(fixture.receiptId(fixture.apply(member, status().isAccepted())));
        }
        fixture.awaitNumbersInIntakeOrder(receipts);
    }

    @Test
    void healthReportsTheOutageWithoutTakingTheAppDown() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DEGRADED"));
    }
}
