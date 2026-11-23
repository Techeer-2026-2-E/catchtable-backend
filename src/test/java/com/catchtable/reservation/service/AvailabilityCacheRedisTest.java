package com.catchtable.reservation.service;

import com.catchtable.global.redis.RedisEndpoint;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 Redis에서 Lua 조회·저장·무효화 스크립트를 실행한다. CI는 Redis 서비스를 함께 띄운다.
@SpringBootTest(properties = {
        "availability.cache.enabled=true",
        "availability.cache.uri=redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}/15",
        "availability.cache.prefix=ct-test-availability:"})
@AutoConfigureMockMvc
@DirtiesContext
class AvailabilityCacheRedisTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T09:00:00+09:00");

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired @Qualifier("availabilityRedis") RedisEndpoint redis;
    @Autowired AvailabilityCache cache;
    private PaymentTestData data;
    private Long owner;
    private Long store;

    @BeforeEach
    void prepare() {
        data = new PaymentTestData(jdbc);
        owner = data.member("OWNER");
        store = data.store(owner);
        WaitingBusinessHourFixture.saveAllDays(jdbc, store, LocalTime.of(13, 0), LocalTime.of(22, 0));
        cache.startTracking(); // 앱 시작 이벤트와 경합하지 않게 추적을 먼저 켠다. 켜기 전 조회는 캐시를 쓰지 않는다.
        // 준비 단계의 쓰기도 무효화를 만든다. 반영이 끝난 뒤 조회해야 적중 여부가 이 테스트의 동작만 반영한다.
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM availability_cache_invalidation WHERE store_id = ?", Long.class, store) == 0);
    }

    @AfterEach
    void clean() {
        // 이 컨텍스트가 켠 DB 전체 추적을 되돌려 다른 테스트의 쓰기에 무효화 행이 생기지 않게 한다.
        jdbc.update("UPDATE availability_cache_control SET tracking = false WHERE id = 1");
        jdbc.update("DELETE FROM availability_cache_invalidation WHERE store_id = ?", store);
        WaitingBusinessHourFixture.clear(jdbc, store);
        data.deleteStore(store, List.of(owner));
    }

    @Test
    void repeatedReadsHitRedisAndATableChangeReplacesTheStoreGeneration() throws Exception {
        double computed = count("availability.cache.compute");
        double hits = count("availability.cache.hit");

        read().andExpect(jsonPath("$.slots[0].availableTableCount").value(1));
        assertThat(count("availability.cache.compute")).isEqualTo(computed + 1);
        read().andExpect(jsonPath("$.slots[0].availableTableCount").value(1));
        assertThat(count("availability.cache.hit")).isEqualTo(hits + 1);
        assertThat(count("availability.cache.compute")).isEqualTo(computed + 1);

        String generation = storeGeneration();
        assertThat(generation).isNotNull();
        jdbc.update("INSERT INTO store_table (store_id, table_number, capacity, status) VALUES (?, 2, 4, 'ACTIVE')", store);
        await().atMost(Duration.ofSeconds(5)).until(() -> !Objects.equals(storeGeneration(), generation));

        read().andExpect(jsonPath("$.slots[0].availableTableCount").value(2));
        assertThat(count("availability.cache.compute")).isEqualTo(computed + 2);
    }

    private org.springframework.test.web.servlet.ResultActions read() throws Exception {
        return mvc.perform(get("/api/user/stores/{id}/availability", store)
                        .param("date", NOW.toLocalDate().toString()).param("partySize", "2"))
                .andExpect(status().isOk());
    }

    private String storeGeneration() {
        return redis.use(s -> s.commands().get("ct-test-availability:s:" + store + ":*"));
    }

    private double count(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
