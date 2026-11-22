package com.catchtable.store.controller;

import com.catchtable.store.dto.StoreInfoUpdateRequest;
import com.catchtable.store.search.StoreSearchIndex;
import com.catchtable.store.search.StoreSearchSyncService;
import com.catchtable.store.service.StoreInfoService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
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
        @Autowired StoreInfoService storeInfoService;

        @Test
        @DisplayName("기본값 postgres는 매장을 수정해도 Outbox에 기록하지 않고 동기화 워커도 없다")
        void defaultEngineHasNoOutboxOrWorker() {
            long id = ownedStore(jdbc, "옛골식당");

            storeInfoService.updateInfo(id, new StoreInfoUpdateRequest("새벽감자탕", null, null, null, null, null));

            assertThat(outboxCount(jdbc, id)).isZero();
            assertThat(context.getBeansOfType(StoreSearchSyncService.class)).isEmpty();
        }

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
        @Autowired StoreInfoService storeInfoService;
        @Autowired TransactionTemplate transactionTemplate;

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("롤백된 매장 수정은 Outbox에 남지 않는다")
        void rolledBackUpdateLeavesNoOutbox() {
            long id = ownedStore(jdbc, "옛골식당");
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    storeInfoService.updateInfo(id, new StoreInfoUpdateRequest("새벽감자탕", null, null, null, null, null));
                    assertThat(outboxCount(jdbc, id)).isEqualTo(1);
                    status.setRollbackOnly();
                });
                assertThat(outboxCount(jdbc, id)).isZero();
            } finally {
                Long owner = jdbc.queryForObject("SELECT owner_id FROM store WHERE id = ?", Long.class, id);
                jdbc.update("DELETE FROM store WHERE id = ?", id);
                jdbc.update("DELETE FROM member WHERE id = ?", owner);
            }
        }

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

    private static long ownedStore(JdbcTemplate jdbc, String name) {
        long owner = jdbc.queryForObject(
                "INSERT INTO member(name, phone, user_type) VALUES ('owner', '000', 'OWNER') RETURNING id", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, ?, 'KOREAN', '서울특별시 중구 세종대로 1', false, 120, 30, 10) RETURNING id
                """, Long.class, owner, name);
    }

    private static int outboxCount(JdbcTemplate jdbc, long storeId) {
        return jdbc.queryForObject("SELECT count(*) FROM store_search_outbox WHERE store_id = ?", Integer.class, storeId);
    }

    private static long store(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO store (name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', '서울특별시 송파구 올림픽로 1', false, 120, 30, 10) RETURNING id
                """, Long.class, name);
    }
}
