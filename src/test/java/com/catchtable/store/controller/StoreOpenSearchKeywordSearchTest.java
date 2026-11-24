package com.catchtable.store.controller;

import com.catchtable.store.dto.StoreInfoUpdateRequest;
import com.catchtable.store.search.StoreReindexService;
import com.catchtable.store.search.StoreSearchOutbox;
import com.catchtable.store.search.StoreSearchSyncService;
import com.catchtable.store.service.StoreInfoService;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "catchtable.search.engine=opensearch")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Transactional
@Testcontainers
class StoreOpenSearchKeywordSearchTest {

    // docker/opensearch/Dockerfile (nori 포함) 이미지
    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            new ImageFromDockerfile("catchtable-opensearch-test", false)
                    .withFileFromPath(".", Path.of("docker/opensearch")))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void openSearchUrl(DynamicPropertyRegistry registry) {
        registry.add("catchtable.search.opensearch.url",
                () -> "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        registry.add("catchtable.search.opensearch.read-timeout", () -> "10s");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired StoreReindexService reindexService;
    @Autowired StoreInfoService storeInfoService;
    @Autowired StoreSearchOutbox outbox;
    @Autowired StoreSearchSyncService syncService;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    @Test
    @DisplayName("돈까스·돈가스·돈카츠는 같은 매장을 찾는다")
    void porkCutletSpellingsAreSynonyms() throws Exception {
        long a = store(null, "호재돈까스", "KOREAN", "서울특별시 송파구 올림픽로 1");
        long b = store(null, "고미돈가스", "KOREAN", "서울특별시 마포구 양화로 1");
        long c = store(null, "또돈카츠", "JAPANESE", "서울특별시 종로구 종로 1");
        long other = store(null, "김밥천국", "KOREAN", "서울특별시 종로구 종로 2");
        reindexService.reindexAll();

        for (String keyword : List.of("돈까스", "돈가스", "돈카츠")) {
            assertThat(search(keyword)).as(keyword).contains(a, b, c).doesNotContain(other);
        }
    }

    @Test
    @DisplayName("초밥과 스시는 같은 매장을 찾는다")
    void sushiSynonyms() throws Exception {
        long a = store(null, "우연초밥", "JAPANESE", "서울특별시 송파구 올림픽로 1");
        long b = store(null, "스시 하쿠샤", "JAPANESE", "서울특별시 강남구 도산대로 1");
        long splitByNori = store(null, "키세츠스시", "JAPANESE", "서울특별시 강남구 도산대로 3");   // nori: 키세 / 츠스시
        long other = store(null, "우동명가", "JAPANESE", "서울특별시 강남구 도산대로 2");
        reindexService.reindexAll();

        assertThat(search("초밥")).contains(a, b, splitByNori).doesNotContain(other);
        assertThat(search("스시")).contains(a, b, splitByNori).doesNotContain(other);
    }

    @Test
    @DisplayName("띄어쓰기가 달라도 매장 이름을 찾는다")
    void spacingIsIgnoredForName() throws Exception {
        long starbucks = store(null, "스타벅스 광장마켓점", "CAFE", "서울특별시 종로구 창경궁로 1");
        long other = store(null, "벅스 버거", "WESTERN", "서울특별시 종로구 창경궁로 2");
        reindexService.reindexAll();

        assertThat(search("스타 벅스")).contains(starbucks).doesNotContain(other);
        assertThat(search("스타벅스")).contains(starbucks);
    }

    @Test
    @DisplayName("지역과 메뉴·이름 단어가 함께 맞는 매장만 찾는다")
    void areaAndMenuWordsMatchTogether() throws Exception {
        long nameInGangnam = store(null, "파스타공방", "WESTERN", "서울특별시 강남구 테헤란로 1");
        long menuInGangnam = store(null, "이탈리안 키친", "WESTERN", "서울특별시 강남구 역삼로 1");
        jdbc.update("INSERT INTO menu (store_id, name, price) VALUES (?, '알리오올리오 파스타', 15000)", menuInGangnam);
        long pastaInMapo = store(null, "파스타하우스", "WESTERN", "서울특별시 마포구 양화로 1");
        long otherInGangnam = store(null, "강남식당", "KOREAN", "서울특별시 강남구 테헤란로 2");
        reindexService.reindexAll();

        assertThat(search("강남 파스타")).contains(nameInGangnam, menuInGangnam)
                .doesNotContain(pastaInMapo, otherInGangnam);
    }

    @Test
    @DisplayName("관련도가 같으면 입점 매장이 먼저, 그다음 id 순이다")
    void enteredStoreComesFirstWhenRelevanceTies() throws Exception {
        long owner = jdbc.queryForObject(
                "INSERT INTO member(name, phone, user_type) VALUES ('owner', '000', 'OWNER') RETURNING id", Long.class);
        long publicFirst = store(null, "행복한 냉면", "KOREAN", "서울특별시 중구 세종대로 1");
        long publicSecond = store(null, "행복한 냉면", "KOREAN", "서울특별시 중구 세종대로 1");
        long entered = store(owner, "행복한 냉면", "KOREAN", "서울특별시 중구 세종대로 1");
        reindexService.reindexAll();

        assertThat(search("냉면")).containsSubsequence(entered, publicFirst, publicSecond);
    }

    @Test
    @DisplayName("카테고리를 함께 주면 해당 카테고리만 찾는다")
    void categoryFilter() throws Exception {
        long cafe = store(null, "모모 카페", "CAFE", "서울특별시 성동구 왕십리로 1");
        long snack = store(null, "모모 분식", "KOREAN", "서울특별시 성동구 왕십리로 2");
        reindexService.reindexAll();

        assertThat(ids(mvc.perform(get("/api/user/stores").param("keyword", "모모").param("category", "CAFE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()))
                .contains(cafe).doesNotContain(snack);
    }

    @Test
    @DisplayName("키워드 검색은 49페이지까지, 50페이지부터 400")
    void keywordPageLimit() throws Exception {
        store(null, "평양냉면", "KOREAN", "서울특별시 중구 세종대로 1");
        reindexService.reindexAll();

        mvc.perform(get("/api/user/stores").param("keyword", "냉면").param("page", "49")).andExpect(status().isOk());
        mvc.perform(get("/api/user/stores").param("keyword", "냉면").param("page", "50"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/user/stores").param("page", "50")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다음 페이지가 있으면 hasNext가 true다")
    void hasNextFromOpenSearch() throws Exception {
        for (int i = 0; i < 3; i++) store(null, "옥천냉면 " + i, "KOREAN", "서울특별시 중구 세종대로 1");
        reindexService.reindexAll();

        JsonNode first = mapper.readTree(mvc.perform(get("/api/user/stores").param("keyword", "옥천냉면").param("size", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.path("stores")).hasSize(2);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("재색인하면 별칭이 새 색인을 가리키고 이전 색인은 지운다")
    void reindexSwitchesAliasAndDeletesOldIndex() {
        String first = reindexService.reindexAll();
        String second = reindexService.reindexAll();

        RestClient client = RestClient.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        JsonNode aliases = client.get().uri("/_alias/stores").retrieve().body(JsonNode.class);
        JsonNode indices = client.get().uri("/_cat/indices/stores_v*?format=json&h=index").retrieve().body(JsonNode.class);
        assertThat(aliases.has(second)).isTrue();
        assertThat(indices).hasSize(1);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("매장 이름을 바꾸면 워커 1회 실행 후 새 이름으로 검색된다")
    void renamedStoreIsSearchableAfterSync() throws Exception {
        long owner = owner();
        long id = store(owner, "옛골식당", "KOREAN", "서울특별시 중구 세종대로 1");
        reindexService.reindexAll();

        storeInfoService.updateInfo(id, new StoreInfoUpdateRequest("새벽감자탕", null, null, null, null, null));
        entityManager.flush();
        assertThat(outboxCount(id)).isEqualTo(1);

        syncService.syncDue();

        assertThat(search("감자탕")).contains(id);
        assertThat(search("옛골식당")).doesNotContain(id);
        assertThat(outboxCount(id)).isZero();
    }

    @Test
    @DisplayName("없어진 매장은 동기화 때 색인에서 지운다")
    void deletedStoreIsRemovedFromIndex() throws Exception {
        long id = store(null, "사라질국밥", "KOREAN", "서울특별시 중구 세종대로 1");
        reindexService.reindexAll();
        assertThat(search("사라질국밥")).contains(id);

        jdbc.update("DELETE FROM store WHERE id = ?", id);
        outbox.record(id);
        syncService.syncDue();

        assertThat(search("사라질국밥")).doesNotContain(id);
    }

    @Test
    @DisplayName("OpenSearch 반영이 실패하면 시도 횟수만 늘리고 행을 남겼다가, 복구 후 반영한다")
    void failedSyncIsRetriedAfterRecovery() throws Exception {
        long id = store(null, "재시도칼국수", "KOREAN", "서울특별시 중구 세종대로 1");
        reindexService.reindexAll();
        jdbc.update("UPDATE store SET name = '복구된칼국수' WHERE id = ?", id);
        outbox.record(id);

        RestClient client = RestClient.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        writeBlock(client, true);
        try {
            syncService.syncDue();
        } finally {
            writeBlock(client, false);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM store_search_outbox WHERE store_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM store_search_outbox WHERE store_id = ?", String.class, id))
                .isNotBlank();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > created_at FROM store_search_outbox WHERE store_id = ?",
                Boolean.class, id)).isTrue();
        assertThat(search("복구된칼국수")).doesNotContain(id);

        jdbc.update("UPDATE store_search_outbox SET next_attempt_at = next_attempt_at - INTERVAL '1 hour' WHERE store_id = ?", id);
        syncService.syncDue();

        assertThat(search("복구된칼국수")).contains(id);
        assertThat(outboxCount(id)).isZero();
    }

    @Test
    @DisplayName("키워드 + 날짜·인원은 동의어 매장 중 예약 가능한 곳만 관련도 순으로 찾는다")
    void keywordWithDateAndPartySize() throws Exception {
        LocalDate date = LocalDate.now(clock).plusDays(1);
        long owner = owner();
        long cutlet = bookable(owner, "호재돈까스", date);
        long cutletPlain = bookable(owner, "돈까스", date);
        long noTable = store(owner, "고미돈가스", "KOREAN", "서울특별시 마포구 양화로 1");
        hours(noTable, date);
        long closed = store(owner, "또돈카츠", "JAPANESE", "서울특별시 종로구 종로 1");
        table(closed);
        long otherKeyword = bookable(owner, "김밥천국", date);
        reindexService.reindexAll();

        List<Long> relevance = search("돈가스").stream().filter(id -> id == cutlet || id == cutletPlain).toList();
        List<Long> result = ids(mvc.perform(get("/api/user/stores").param("keyword", "돈가스")
                        .param("date", date.toString()).param("time", "12:00").param("partySize", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(relevance).hasSize(2);
        assertThat(result).containsExactlyElementsOf(relevance).doesNotContain(noTable, closed, otherKeyword);
    }

    @Test
    @DisplayName("키워드 + 인원만(날짜 없음)도 동의어로 찾고 테이블 없는 매장은 뺀다")
    void keywordWithPartySizeOnly() throws Exception {
        long owner = owner();
        long withTable = store(owner, "호재돈까스", "KOREAN", "서울특별시 송파구 올림픽로 1");
        table(withTable);
        long noTable = store(owner, "고미돈가스", "KOREAN", "서울특별시 마포구 양화로 1");
        reindexService.reindexAll();

        List<Long> result = ids(mvc.perform(get("/api/user/stores").param("keyword", "돈카츠").param("partySize", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(result).contains(withTable).doesNotContain(noTable);
        mvc.perform(get("/api/user/stores").param("keyword", "돈카츠").param("partySize", "2").param("page", "50"))
                .andExpect(status().isBadRequest());
    }

    private void writeBlock(RestClient client, boolean blocked) {
        client.put().uri("/stores/_settings").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("index.blocks.write", blocked)).retrieve().toBodilessEntity();
    }

    private int outboxCount(long storeId) {
        return jdbc.queryForObject("SELECT count(*) FROM store_search_outbox WHERE store_id = ?", Integer.class, storeId);
    }

    private long owner() {
        return jdbc.queryForObject(
                "INSERT INTO member(name, phone, user_type) VALUES ('owner', '000', 'OWNER') RETURNING id", Long.class);
    }

    private List<Long> search(String keyword) throws Exception {
        return ids(mvc.perform(get("/api/user/stores").param("keyword", keyword).param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Long> ids(String body) throws Exception {
        List<Long> ids = new ArrayList<>();
        mapper.readTree(body).path("stores").forEach(store -> ids.add(store.path("id").asLong()));
        return ids;
    }

    private long store(Long ownerId, String name, String category, String address) {
        return jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, ?, ?, ?, false, 120, 30, 10) RETURNING id
                """, Long.class, ownerId, name, category, address);
    }

    // 영업시간(해당 요일 10~22시)·2~4인 테이블이 있는 입점 매장
    private long bookable(long owner, String name, LocalDate date) {
        long id = store(owner, name, "KOREAN", "서울특별시 송파구 올림픽로 1");
        hours(id, date);
        table(id);
        return id;
    }

    private void hours(long storeId, LocalDate date) {
        jdbc.update("INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time) VALUES (?, ?, '10:00', '22:00')",
                storeId, date.getDayOfWeek().getValue());
    }

    private void table(long storeId) {
        jdbc.update("INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status) VALUES (?, 1, 2, 4, 'ACTIVE')",
                storeId);
    }
}
