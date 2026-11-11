package com.catchtable.store.controller;

import com.catchtable.store.dto.MapStoreResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기준점은 부산시청. 서울 공공데이터가 테스트 DB에 적재돼 있어도 결과가 섞이지 않는다.
// 위도 0.001도 = 약 111m (경도는 고정)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserStoreMapControllerTest {

    private static final double LAT = 35.1796;
    private static final double LNG = 129.0756;
    private static final String MAP = "/api/user/stores/map/radius?lat=" + LAT + "&lng=" + LNG;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("프런트가 요청한 필드를 모두 내려준다")
    void returnsRequestedFields() throws Exception {
        insertStore("서울 매장", "서울특별시 금천구 가산디지털1로 123", LAT + 0.0009, LNG, null);

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].id").isNumber())
                .andExpect(jsonPath("$.stores[0].name", is("서울 매장")))
                .andExpect(jsonPath("$.stores[0].categoryName", is("한식")))
                .andExpect(jsonPath("$.stores[0].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.stores[0].latitude", is(LAT + 0.0009)))
                .andExpect(jsonPath("$.stores[0].longitude", is(LNG)))
                .andExpect(jsonPath("$.stores[0].area", is("금천구")))
                .andExpect(jsonPath("$.stores[0].distanceMeters", allOf(greaterThan(98), lessThan(103))))
                .andExpect(jsonPath("$.stores[0].reservationEnabled", is(false)))
                .andExpect(jsonPath("$.stores[0].waitingAvailable", is(false)));
    }

    @Test
    @DisplayName("점주가 있는 매장은 reservationEnabled가 true이고 공공 매장은 false다")
    void reservationEnabledFollowsOwner() throws Exception {
        long ownerId = insertOwner();
        insertStore("공공 매장", "서울특별시 강남구 테헤란로 1", LAT + 0.0009, LNG, null);
        insertStoreWithOwner("입점 매장", LAT + 0.0018, LNG, ownerId);

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].name", is("공공 매장")))
                .andExpect(jsonPath("$.stores[0].reservationEnabled", is(false)))
                .andExpect(jsonPath("$.stores[1].name", is("입점 매장")))
                .andExpect(jsonPath("$.stores[1].reservationEnabled", is(true)));
    }

    @Test
    @DisplayName("대표 사진 주소를 내려준다")
    void includesImageUrl() throws Exception {
        insertStore("사진 매장", "서울특별시 마포구 월드컵로 1", LAT + 0.0009, LNG, "https://example.com/store.jpg");

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].imageUrl", is("https://example.com/store.jpg")));
    }

    @Test
    @DisplayName("구로 끝나는 단어가 두 번째에 없는 주소는 area가 null이다")
    void areaIsNullForUnexpectedAddress() throws Exception {
        insertStore("다른 형식", "부산 테스트 주소", LAT + 0.0009, LNG, null);

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].area").value(nullValue()));
    }

    @Test
    @DisplayName("가까운 순으로 정렬하고 거리가 같으면 id 순서로 고정한다")
    void sortsByDistanceThenId() throws Exception {
        insertStore("먼 매장", "서울특별시 중구 세종대로 1", LAT + 0.0081, LNG, null);
        insertStore("먼저 등록", "서울특별시 중구 세종대로 1", LAT + 0.0009, LNG, null);
        insertStore("나중 등록", "서울특별시 중구 세종대로 1", LAT + 0.0009, LNG, null);

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("먼저 등록", "나중 등록", "먼 매장")));
    }

    @Test
    @DisplayName("반경 1km 안쪽 경계는 포함하고 바깥쪽은 제외한다")
    void appliesOneKilometerRadius() throws Exception {
        insertStore("반경 안", "서울특별시 중구 세종대로 1", LAT + 0.00898, LNG, null);   // 약 998m
        insertStore("반경 밖", "서울특별시 중구 세종대로 1", LAT + 0.00902, LNG, null);   // 약 1003m

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("반경 안")));
    }

    @Test
    @DisplayName("좌표가 없는 매장은 제외한다")
    void excludesStoreWithoutCoordinate() throws Exception {
        insertStore("좌표 있음", "서울특별시 중구 세종대로 1", LAT + 0.0009, LNG, null);
        jdbcTemplate.update("""
                INSERT INTO store (name, category, address,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES ('좌표 없음', 'KOREAN', '서울특별시 중구 세종대로 1', 120, 30, 10)
                """);

        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("좌표 있음")));
    }

    @Test
    @DisplayName("페이지를 나눠 가까운 순으로 이어 주고 마지막 페이지는 hasNext가 false다")
    void paginatesInDistanceOrder() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertStore("p" + i, "서울특별시 중구 세종대로 1", LAT + 0.0009 * i, LNG, null);   // 100m 간격
        }

        mockMvc.perform(get(MAP + "&page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("p1", "p2")))
                .andExpect(jsonPath("$.hasNext", is(true)));
        mockMvc.perform(get(MAP + "&page=2&size=2"))
                .andExpect(jsonPath("$.stores[*].name", contains("p5")))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("page와 size를 생략하면 0페이지에 10개씩이고 매장이 없으면 빈 목록이다")
    void usesDefaultPageAndReturnsEmpty() throws Exception {
        mockMvc.perform(get(MAP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", empty()))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/user/stores/map/radius?lng=129.0756",
            "/api/user/stores/map/radius?lat=35.1796",
            "/api/user/stores/map/radius?lat=91&lng=129.0756",
            "/api/user/stores/map/radius?lat=35.1796&lng=181",
            "/api/user/stores/map/radius?lat=NaN&lng=129.0756",
            "/api/user/stores/map/radius?lat=35.1796&lng=129.0756&page=-1",
            "/api/user/stores/map/radius?lat=35.1796&lng=129.0756&size=0",
            "/api/user/stores/map/radius?lat=35.1796&lng=129.0756&size=51"
    })
    @DisplayName("좌표가 없거나 범위를 벗어나거나 page·size가 잘못되면 400이다")
    void rejectsInvalidInput(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "서울특별시 금천구 가산디지털1로 123",
            "  서울특별시   금천구   가산디지털1로  ",
    })
    @DisplayName("주소의 두 번째 단어가 구이면 area로 꺼낸다. 이중 공백이 있어도 같다")
    void areaOfExtractsGu(String address) {
        assertThat(MapStoreResponse.areaOf(address)).isEqualTo("금천구");
    }

    @ParameterizedTest(name = "주소=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "서울특별시", "경기도 성남시 분당구 정자동", "부산 테스트 주소"})
    @DisplayName("주소가 없거나 두 번째 단어가 구로 끝나지 않으면 area는 null이다")
    void areaOfReturnsNullForUnexpectedAddress(String address) {
        assertThat(MapStoreResponse.areaOf(address)).isNull();
    }

    private void insertStore(String name, String address, double latitude, double longitude, String imageUrl) {
        jdbcTemplate.update("""
                INSERT INTO store (name, category, address, latitude, longitude, image_url,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', ?, ?, ?, ?, 120, 30, 10)
                """, name, address, latitude, longitude, imageUrl);
    }

    private long insertOwner() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('점주', '010-0000-0000', 'OWNER')
                RETURNING id
                """, Long.class);
    }

    private void insertStoreWithOwner(String name, double latitude, double longitude, long ownerId) {
        jdbcTemplate.update("""
                INSERT INTO store (owner_id, name, category, address, latitude, longitude,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, ?, 'KOREAN', '서울특별시 강남구 테헤란로 1', ?, ?, 120, 30, 10)
                """, ownerId, name, latitude, longitude);
    }
}
