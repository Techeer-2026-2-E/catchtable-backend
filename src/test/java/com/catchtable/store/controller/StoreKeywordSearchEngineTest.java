package com.catchtable.store.controller;

import com.catchtable.store.search.StoreSearchIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// OpenSearch 없이 확인하는 검색 엔진 설정·장애 대체
class StoreKeywordSearchEngineTest {

    @Nested
    @SpringBootTest
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @AutoConfigureMockMvc
    @Transactional
    class Postgres {
        @Autowired MockMvc mvc;
        @Autowired JdbcTemplate jdbc;
        @Autowired ApplicationContext context;

        @Test
        @DisplayName("기본값 postgres는 OpenSearch 연결을 만들지 않고 DB 키워드 검색을 그대로 쓴다")
        void defaultEngineUsesDatabase() throws Exception {
            long id = store(jdbc, "호재돈까스");

            assertThat(context.getBeansOfType(StoreSearchIndex.class)).isEmpty();
            mvc.perform(get("/api/user/stores").param("keyword", "돈까스").param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stores[*].id", hasItem((int) id)));
            mvc.perform(get("/api/user/stores").param("keyword", "돈까스").param("page", "60"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stores", hasSize(0)));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "catchtable.search.engine=opensearch",
            "catchtable.search.opensearch.url=http://127.0.0.1:1",
            "catchtable.search.opensearch.connect-timeout=200ms"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @AutoConfigureMockMvc
    @Transactional
    class OpenSearchDown {
        @Autowired MockMvc mvc;
        @Autowired JdbcTemplate jdbc;

        @Test
        @DisplayName("OpenSearch에 연결할 수 없으면 DB 키워드 검색으로 대체한다")
        void fallsBackToDatabase() throws Exception {
            long id = store(jdbc, "호재돈까스");

            mvc.perform(get("/api/user/stores").param("keyword", "돈까스").param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stores[*].id", hasItem((int) id)));
        }

        @Test
        @DisplayName("대체 중에도 키워드 검색 50페이지부터는 400")
        void pageLimitStillApplies() throws Exception {
            mvc.perform(get("/api/user/stores").param("keyword", "돈까스").param("page", "50"))
                    .andExpect(status().isBadRequest());
        }
    }

    private static long store(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO store (name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', '서울특별시 송파구 올림픽로 1', false, 120, 30, 10) RETURNING id
                """, Long.class, name);
    }
}
