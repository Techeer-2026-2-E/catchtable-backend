package com.catchtable.store.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행. 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserStoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ownerId;
    private Long storeId;

    @BeforeEach
    void setUp() {
        ownerId = jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('점주', '010-0000-0000', 'OWNER')
                RETURNING id""", Long.class);
        storeId = jdbcTemplate.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, '스케줄 청담', 'WESTERN', '서울 강남구', TRUE, 120, 30, 10)
                RETURNING id""", Long.class, ownerId);
    }

    private void insertHour(int dayOfWeek, String open, String close, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time, deleted_at)
                VALUES (?, ?, ?::time, ?::time, CASE WHEN ? THEN now() END)""",
                storeId, dayOfWeek, open, close, deleted);
    }

    private void insertMenu(String name, int price, String description) {
        jdbcTemplate.update("INSERT INTO menu (store_id, name, price, description) VALUES (?, ?, ?, ?)",
                storeId, name, price, description);
    }

    private Long insertStore(String name, String category) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, ?, ?, '서울 강남구', TRUE, 120, 30, 10)
                RETURNING id""", Long.class, ownerId, name, category);
    }

    private void insertTable(Long targetStoreId, int tableNumber, int capacity) {
        jdbcTemplate.update("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, ?, ?, 'ACTIVE')""", targetStoreId, tableNumber, capacity);
    }

    // 테스트 DB에 공공데이터가 있어도 내 매장이 들어 있는 페이지를 계산해서 부른다
    private int pageOf(int size) {
        Long before = jdbcTemplate.queryForObject("SELECT count(*) FROM store WHERE id < ?", Long.class, storeId);
        return (int) (before / size);
    }

    // 테스트 DB에 이미 테이블이 있는 매장이 있어도 기대값이 흔들리지 않게 직접 센다
    private long seatableStoreCount(int partySize) {
        return jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT store_id) FROM store_table
                WHERE status = 'ACTIVE' AND min_capacity <= ? AND ? <= capacity""",
                Long.class, partySize, partySize);
    }

    @Test
    @DisplayName("매장 기본 정보와 영업시간을 요일 순으로 조회한다")
    void getStoreDetail() throws Exception {
        insertHour(7, "11:00", "05:00", false);   // 일요일, 다음 날 새벽 마감
        insertHour(1, "11:00", "22:00", false);   // 월요일

        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(storeId))
                .andExpect(jsonPath("$.name").value("스케줄 청담"))
                .andExpect(jsonPath("$.category").value("WESTERN"))
                .andExpect(jsonPath("$.categoryName").value("양식"))
                .andExpect(jsonPath("$.address").value("서울 강남구"))
                .andExpect(jsonPath("$.waitingAvailable").value(true))
                .andExpect(jsonPath("$.reservationEnabled").value(true))
                .andExpect(jsonPath("$.businessHours", hasSize(2)))
                .andExpect(jsonPath("$.businessHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.businessHours[1].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.businessHours[1].closingTime").value("05:00:00"));
    }

    @Test
    @DisplayName("소프트 삭제된 요일(휴무)은 영업시간에 나오지 않는다")
    void excludeDeletedHours() throws Exception {
        insertHour(1, "11:00", "22:00", false);
        insertHour(2, "11:00", "22:00", true);

        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessHours", hasSize(1)))
                .andExpect(jsonPath("$.businessHours[0].dayOfWeek").value("MONDAY"));
    }

    @Test
    @DisplayName("메뉴를 등록순으로 함께 조회한다")
    void includeMenus() throws Exception {
        insertMenu("런치 코스", 45000, "점심 한정");
        insertMenu("콜키지", 0, null);

        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(2)))
                .andExpect(jsonPath("$.menus[0].name").value("런치 코스"))
                .andExpect(jsonPath("$.menus[0].price").value(45000))
                .andExpect(jsonPath("$.menus[0].description").value("점심 한정"))
                .andExpect(jsonPath("$.menus[1].name").value("콜키지"))
                .andExpect(jsonPath("$.menus[1].price").value(0));
    }

    @Test
    @DisplayName("메뉴가 없으면 빈 배열")
    void emptyMenus() throws Exception {
        mockMvc.perform(get("/api/user/stores/{storeId}", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(0)));
    }

    @Test
    @DisplayName("매장 목록에 매장 사진 주소를 내려준다")
    void listIncludesImageUrl() throws Exception {
        jdbcTemplate.update("UPDATE store SET image_url = ? WHERE id = ?",
                "https://example.com/store.jpg", storeId);

        // 테스트 DB에 공공데이터가 있어도 섞이지 않게 내 매장이 들어 있는 페이지를 골라서 확인한다
        mockMvc.perform(get("/api/user/stores?size=50&page=" + pageOf(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[?(@.id == " + storeId + ")].imageUrl",
                        contains("https://example.com/store.jpg")));
    }

    @Test
    @DisplayName("사진이 없는 매장은 목록에서 imageUrl이 null이다")
    void listImageUrlIsNullWhenMissing() throws Exception {
        mockMvc.perform(get("/api/user/stores?size=50&page=" + pageOf(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[?(@.id == " + storeId + ")].imageUrl", contains(nullValue())));
    }

    @Test
    @DisplayName("page와 size를 생략하면 0페이지에 10개 이하로 내려준다")
    void listDefaultsToFirstPageOfTen() throws Exception {
        mockMvc.perform(get("/api/user/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.stores", hasSize(lessThanOrEqualTo(10))));
    }

    @Test
    @DisplayName("다음 페이지가 있으면 hasNext가 true이고 size개만 내려준다")
    void listHasNextWhenMoreStores() throws Exception {
        for (int i = 0; i < 11; i++) {
            insertStore("추가 매장 " + i, "KOREAN");   // 합쳐서 12개 이상이 되게 한다
        }

        mockMvc.perform(get("/api/user/stores?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(10)))
                .andExpect(jsonPath("$.hasNext", is(true)));
    }

    @Test
    @DisplayName("마지막 페이지는 hasNext가 false이고, 범위를 넘으면 빈 목록이다")
    void listLastPageAndBeyond() throws Exception {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM store", Long.class);
        int lastPage = (int) ((total - 1) / 10);
        int lastCount = (int) ((total - 1) % 10) + 1;

        mockMvc.perform(get("/api/user/stores?size=10&page=" + lastPage))
                .andExpect(jsonPath("$.stores", hasSize(lastCount)))
                .andExpect(jsonPath("$.hasNext", is(false)));

        mockMvc.perform(get("/api/user/stores?size=10&page=" + (lastPage + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(0)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("매장은 id 오름차순으로 내려준다")
    void listIsOrderedById() throws Exception {
        insertStore("추가 매장 A", "KOREAN");
        insertStore("추가 매장 B", "KOREAN");

        String body = mockMvc.perform(get("/api/user/stores?size=50&page=" + pageOf(50)))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.stores[*].id");

        assertThat(ids).isSorted();
    }

    @Test
    @DisplayName("카테고리를 주면 그 카테고리 매장만 페이지로 내려준다")
    void listFiltersByCategoryWithPaging() throws Exception {
        insertStore("한식 매장", "KOREAN");

        mockMvc.perform(get("/api/user/stores?category=WESTERN&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(lessThanOrEqualTo(5))))
                .andExpect(jsonPath("$.stores[*].category", everyItem(is("WESTERN"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/user/stores?page=-1",
            "/api/user/stores?size=0",
            "/api/user/stores?size=51"
    })
    @DisplayName("page·size가 잘못되면 400이다")
    void listRejectsInvalidPaging(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인원 필터는 그 인원을 앉힐 수 있는 매장만 내려준다")
    void filterByPartySize() throws Exception {
        insertTable(storeId, 1, 4);
        Long noTableStoreId = insertStore("테이블 없는 매장", "WESTERN");

        mockMvc.perform(get("/api/user/stores?partySize=4&size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[?(@.id == " + storeId + ")]", hasSize(1)))
                .andExpect(jsonPath("$.stores[?(@.id == " + noTableStoreId + ")]", hasSize(0)));
    }

    @Test
    @DisplayName("인원 필터 결과도 page·size로 잘라서 내려준다")
    void filterByPartySizeWithPaging() throws Exception {
        insertTable(storeId, 1, 4);
        for (int i = 0; i < 4; i++) {
            insertTable(insertStore("테이블 매장 " + i, "KOREAN"), 1, 4);
        }
        long total = seatableStoreCount(4);          // 위에서 만든 5개 이상
        int lastPage = (int) ((total - 1) / 2);
        int lastCount = (int) ((total - 1) % 2) + 1;

        mockMvc.perform(get("/api/user/stores?partySize=4&page=0&size=2"))
                .andExpect(jsonPath("$.stores", hasSize(2)))
                .andExpect(jsonPath("$.hasNext", is(true)));

        mockMvc.perform(get("/api/user/stores?partySize=4&size=2&page=" + lastPage))
                .andExpect(jsonPath("$.stores", hasSize(lastCount)))
                .andExpect(jsonPath("$.hasNext", is(false)));

        mockMvc.perform(get("/api/user/stores?partySize=4&size=2&page=" + (lastPage + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(0)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("인원 필터 결과도 id 오름차순이다")
    void filterResultIsOrderedById() throws Exception {
        insertTable(storeId, 1, 4);
        insertTable(insertStore("테이블 매장 A", "KOREAN"), 1, 4);
        insertTable(insertStore("테이블 매장 B", "KOREAN"), 1, 4);

        String body = mockMvc.perform(get("/api/user/stores?partySize=4&size=50"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.stores[*].id");

        assertThat(ids).isSorted();
    }

    @Test
    @DisplayName("날짜·시간 필터는 그 시각에 영업하는 매장만 내려준다")
    void filterByDateTime() throws Exception {
        // 내일 19시로 잡아 예약 가능 기간(기본 30일)과 마감 안에 항상 들어오게 한다
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        insertHour(tomorrow.getDayOfWeek().getValue(), "11:00", "22:00", false);
        Long noHourStoreId = insertStore("영업시간 없는 매장", "WESTERN");

        mockMvc.perform(get("/api/user/stores?date=" + tomorrow + "&time=19:00&size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[?(@.id == " + storeId + ")]", hasSize(1)))
                .andExpect(jsonPath("$.stores[?(@.id == " + noHourStoreId + ")]", hasSize(0)));
    }

    @Test
    @DisplayName("날짜와 시간 중 하나만 주면 400이다")
    void dateWithoutTimeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/user/stores?date=2026-11-01"))
                .andExpect(status().isBadRequest());
    }

    // 이름 검색 결과의 id 목록 (공공데이터가 섞여 있어도 한 페이지에 다 들어오게 size를 크게 준다)
    private List<Integer> searchIds(String keyword, String... extraParams) throws Exception {
        var request = get("/api/user/stores").queryParam("keyword", keyword).queryParam("size", "50");
        for (int i = 0; i < extraParams.length; i += 2) {
            request.queryParam(extraParams[i], extraParams[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.stores[*].id");
    }

    @Test
    @DisplayName("이름 일부가 맞는 매장만 대소문자 구분 없이 내려준다")
    void keywordMatchesNamePartiallyIgnoringCase() throws Exception {
        Long first = insertStore("ZQX 파스타", "WESTERN");
        Long second = insertStore("맛있는 zqxbar", "WESTERN");
        Long other = insertStore("무관한 매장", "WESTERN");

        assertThat(searchIds("zqx")).containsExactly(first.intValue(), second.intValue());
        assertThat(searchIds("ZQX")).containsExactly(first.intValue(), second.intValue());
        assertThat(searchIds("스케줄")).contains(storeId.intValue()).doesNotContain(other.intValue());
    }

    @Test
    @DisplayName("한 글자 키워드도 검색된다")
    void keywordAllowsSingleCharacter() throws Exception {
        Long target = insertStore("Ω", "WESTERN");

        assertThat(searchIds("Ω")).contains(target.intValue());
    }

    @Test
    @DisplayName("앞뒤 공백은 무시하고, 공백뿐인 키워드는 조건이 없는 것으로 본다")
    void keywordIsTrimmedAndBlankMeansNoCondition() throws Exception {
        Long target = insertStore("zqx 공백", "WESTERN");

        assertThat(searchIds("  zqx  ")).containsExactly(target.intValue());

        String withBlank = mockMvc.perform(get("/api/user/stores").queryParam("keyword", "   "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String without = mockMvc.perform(get("/api/user/stores"))
                .andReturn().getResponse().getContentAsString();
        assertThat(withBlank).isEqualTo(without);
    }

    @Test
    @DisplayName("% _ \\ 는 와일드카드가 아니라 문자 그대로 찾는다")
    void keywordTreatsWildcardsAsLiterals() throws Exception {
        Long percent = insertStore("zqp100%할인", "WESTERN");
        Long digit = insertStore("zqp100점할인", "WESTERN");
        Long underscore = insertStore("zqu_bar", "WESTERN");
        insertStore("zquxbar", "WESTERN");
        Long backslash = insertStore("zqb\\bar", "WESTERN");
        insertStore("zqbxbar", "WESTERN");

        assertThat(searchIds("zqp100%")).containsExactly(percent.intValue());
        assertThat(searchIds("%할인")).contains(percent.intValue()).doesNotContain(digit.intValue());
        assertThat(searchIds("zqu_bar")).containsExactly(underscore.intValue());
        assertThat(searchIds("zqb\\bar")).containsExactly(backslash.intValue());
    }

    @Test
    @DisplayName("키워드는 점주 없는 공공데이터 매장도 찾는다")
    void keywordFindsPublicStores() throws Exception {
        Long publicStore = jdbcTemplate.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (NULL, 'zqpub 공공 식당', 'KOREAN', '서울', FALSE, 60, 30, 10)
                RETURNING id""", Long.class);

        assertThat(searchIds("zqpub")).containsExactly(publicStore.intValue());
    }

    @Test
    @DisplayName("키워드는 카테고리·인원 조건과 함께 모두 맞아야 한다")
    void keywordCombinesWithOtherConditions() throws Exception {
        Long koreanSeatable = insertStore("zqc 한식 A", "KOREAN");
        Long koreanNoTable = insertStore("zqc 한식 B", "KOREAN");
        Long western = insertStore("zqc 양식", "WESTERN");
        insertTable(koreanSeatable, 1, 4);
        insertTable(western, 1, 4);

        assertThat(searchIds("zqc", "category", "KOREAN"))
                .containsExactly(koreanSeatable.intValue(), koreanNoTable.intValue());
        assertThat(searchIds("zqc", "partySize", "4"))
                .containsExactly(koreanSeatable.intValue(), western.intValue());
        assertThat(searchIds("zqc", "category", "KOREAN", "partySize", "4"))
                .containsExactly(koreanSeatable.intValue());
    }

    @Test
    @DisplayName("키워드 결과도 id 순으로 page·size에 맞춰 자르고 hasNext를 계산한다")
    void keywordResultsArePaged() throws Exception {
        Long a = insertStore("zqg 하나", "WESTERN");
        Long b = insertStore("zqg 둘", "WESTERN");
        Long c = insertStore("zqg 셋", "WESTERN");

        mockMvc.perform(get("/api/user/stores").queryParam("keyword", "zqg").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].id", contains(a.intValue(), b.intValue())))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get("/api/user/stores").queryParam("keyword", "zqg").queryParam("size", "2")
                        .queryParam("page", "1"))
                .andExpect(jsonPath("$.stores[*].id", contains(c.intValue())))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("맞는 매장이 없으면 빈 목록이다")
    void keywordWithoutMatchIsEmpty() throws Exception {
        mockMvc.perform(get("/api/user/stores").queryParam("keyword", "zz없는이름zz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("없는 매장이면 404")
    void storeNotFound() throws Exception {
        mockMvc.perform(get("/api/user/stores/{storeId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }
}
