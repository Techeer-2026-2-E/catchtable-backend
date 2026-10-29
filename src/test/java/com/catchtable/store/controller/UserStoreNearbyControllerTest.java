package com.catchtable.store.controller;

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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기준점은 부산시청. 서울 공공데이터가 테스트 DB에 적재돼 있어도 결과가 섞이지 않는다.
// 위도 0.001도 = 약 111m (경도는 고정)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserStoreNearbyControllerTest {

    private static final double LAT = 35.1796;
    private static final double LNG = 129.0756;
    private static final String HOME = "/api/user/stores/home?lat=" + LAT + "&lng=" + LNG;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("가까운 순으로 정렬하고 거리를 미터로 내려준다")
    void sortsByDistanceAscending() throws Exception {
        insertStore("먼 매장", LAT + 0.0081, LNG);       // 약 900m
        insertStore("가까운 매장", LAT + 0.0009, LNG);    // 약 100m
        insertStore("중간 매장", LAT, LNG + 0.0055);      // 약 500m

        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("가까운 매장", "중간 매장", "먼 매장")))
                .andExpect(jsonPath("$.stores[0].distanceMeters", allOf(greaterThan(98), lessThan(103))))
                .andExpect(jsonPath("$.stores[1].distanceMeters", allOf(greaterThan(495), lessThan(505))))
                .andExpect(jsonPath("$.stores[2].distanceMeters", allOf(greaterThan(897), lessThan(904))));
    }

    @Test
    @DisplayName("응답에는 매장 요약 정보가 담기고 점주 없는 미입점 매장도 포함한다")
    void includesStoreSummaryAndUnclaimedStore() throws Exception {
        insertStore("미입점 매장", LAT + 0.0009, LNG);

        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].name", is("미입점 매장")))
                .andExpect(jsonPath("$.stores[0].category", is("KOREAN")))
                .andExpect(jsonPath("$.stores[0].categoryName", is("한식")))
                .andExpect(jsonPath("$.stores[0].address", is("부산 테스트 주소")))
                .andExpect(jsonPath("$.stores[0].waitingAvailable", is(false)))
                .andExpect(jsonPath("$.stores[0].id").isNumber());
    }

    @Test
    @DisplayName("반경 1km 안쪽 경계는 포함하고 바깥쪽은 제외한다")
    void appliesOneKilometerRadius() throws Exception {
        insertStore("반경 안", LAT + 0.00898, LNG);       // 약 998m
        insertStore("반경 밖", LAT + 0.00902, LNG);       // 약 1003m
        insertStore("한참 밖", LAT + 0.0135, LNG);        // 약 1500m

        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("반경 안")));
    }

    @Test
    @DisplayName("좌표가 없는 매장은 제외한다")
    void excludesStoreWithoutCoordinate() throws Exception {
        insertStore("좌표 있음", LAT + 0.0009, LNG);
        insertStoreWithoutCoordinate("좌표 없음");

        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("좌표 있음")))
                .andExpect(jsonPath("$.stores[*].name", not(hasItem("좌표 없음"))));
    }

    @Test
    @DisplayName("거리가 같으면 id 순서로 고정한다")
    void ordersByIdWhenDistanceTies() throws Exception {
        insertStore("먼저 등록", LAT + 0.0009, LNG);
        insertStore("나중 등록", LAT + 0.0009, LNG);

        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("먼저 등록", "나중 등록")));
    }

    @Test
    @DisplayName("page와 size를 생략하면 0페이지에 10개씩이다")
    void usesDefaultPageAndSize() throws Exception {
        insertStore("매장", LAT + 0.0009, LNG);

        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("페이지를 나눠 가까운 순으로 이어 주고 마지막 페이지는 hasNext가 false다")
    void paginatesInDistanceOrder() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertStore("p" + i, LAT + 0.0009 * i, LNG);   // 100m 간격
        }

        mockMvc.perform(get(HOME + "&page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("p1", "p2")))
                .andExpect(jsonPath("$.hasNext", is(true)));
        mockMvc.perform(get(HOME + "&page=1&size=2"))
                .andExpect(jsonPath("$.stores[*].name", contains("p3", "p4")))
                .andExpect(jsonPath("$.hasNext", is(true)));
        mockMvc.perform(get(HOME + "&page=2&size=2"))
                .andExpect(jsonPath("$.stores[*].name", contains("p5")))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("범위를 넘은 페이지는 빈 목록이다")
    void returnsEmptyForPageBeyondLast() throws Exception {
        insertStore("매장", LAT + 0.0009, LNG);

        mockMvc.perform(get(HOME + "&page=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", empty()))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("반경 안에 매장이 없으면 빈 목록이다")
    void returnsEmptyWhenNothingNearby() throws Exception {
        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", empty()))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/user/stores/home?lng=129.0756",
            "/api/user/stores/home?lat=35.1796",
            "/api/user/stores/home?lat=91&lng=129.0756",
            "/api/user/stores/home?lat=-91&lng=129.0756",
            "/api/user/stores/home?lat=35.1796&lng=181",
            "/api/user/stores/home?lat=35.1796&lng=-181",
            "/api/user/stores/home?lat=abc&lng=129.0756",
            "/api/user/stores/home?lat=NaN&lng=129.0756",
            "/api/user/stores/home?lat=35.1796&lng=NaN",
            "/api/user/stores/home?lat=Infinity&lng=129.0756",
            "/api/user/stores/home?lat=35.1796&lng=-Infinity",
            "/api/user/stores/home?lat=35.1796&lng=129.0756&page=-1",
            "/api/user/stores/home?lat=35.1796&lng=129.0756&size=0",
            "/api/user/stores/home?lat=35.1796&lng=129.0756&size=51"
    })
    @DisplayName("좌표가 없거나 범위를 벗어나거나 page·size가 잘못되면 400이다")
    void rejectsInvalidInput(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    private void insertStore(String name, double latitude, double longitude) {
        jdbcTemplate.update("""
                INSERT INTO store (name, category, address, latitude, longitude,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', '부산 테스트 주소', ?, ?, 120, 30, 10)
                """, name, latitude, longitude);
    }

    private void insertStoreWithoutCoordinate(String name) {
        jdbcTemplate.update("""
                INSERT INTO store (name, category, address,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', '부산 테스트 주소', 120, 30, 10)
                """, name);
    }
}
