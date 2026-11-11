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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기준점은 부산시청. 서울 공공데이터가 테스트 DB에 적재돼 있어도 결과가 섞이지 않는다.
// 사각형은 위도 0.02도(약 2.2km) x 경도 0.02도(약 1.8km). 위도 0.001도 = 약 111m
// 마커 조회 상한은 테스트에서 3으로 낮춘다
@SpringBootTest(properties = "store.map.marker-limit=3")
@AutoConfigureMockMvc
@Transactional
class UserStoreMapBoundsControllerTest {

    private static final String BOUNDS = "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856";
    private static final String HERE = "&lat=35.1796&lng=129.0756";
    private static final String LIST = "/api/user/stores/map/bounds?" + BOUNDS + HERE;
    private static final String MARKERS = "/api/user/stores/map/bounds/markers?" + BOUNDS;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---- 목록 ----

    @Test
    @DisplayName("목록은 내 위치에서 가까운 순으로 정렬하고 거리를 미터로 내려준다")
    void listSortsByDistanceFromMyLocation() throws Exception {
        insertStore("먼 매장", 35.1877, 129.0756);        // 약 900m
        insertStore("가까운 매장", 35.1805, 129.0756);     // 약 100m
        insertStore("중간 매장", 35.1796, 129.0811);       // 약 500m

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("가까운 매장", "중간 매장", "먼 매장")))
                .andExpect(jsonPath("$.stores[0].distanceMeters", allOf(greaterThan(98), lessThan(103))))
                .andExpect(jsonPath("$.stores[1].distanceMeters", allOf(greaterThan(495), lessThan(505))))
                .andExpect(jsonPath("$.stores[2].distanceMeters", allOf(greaterThan(897), lessThan(904))));
    }

    @Test
    @DisplayName("거리는 사각형 중심이 아니라 내 위치 기준이다")
    void listUsesMyLocationNotCenter() throws Exception {
        insertStore("남서쪽 매장", 35.1746, 129.0706);
        insertStore("북동쪽 매장", 35.1846, 129.0806);

        // 내 위치를 사각형의 남서쪽 모서리에 둔다
        mockMvc.perform(get("/api/user/stores/map/bounds?" + BOUNDS + "&lat=35.1696&lng=129.0656"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("남서쪽 매장", "북동쪽 매장")));
    }

    @Test
    @DisplayName("목록 응답에는 요청된 필드가 담긴다")
    void listReturnsFields() throws Exception {
        long ownerId = insertOwner();
        insertStoreFull("입점 매장", "서울특별시 금천구 가산디지털1로 123", 35.1805, 129.0756,
                "https://example.com/store.jpg", ownerId);

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].id").isNumber())
                .andExpect(jsonPath("$.stores[0].name", is("입점 매장")))
                .andExpect(jsonPath("$.stores[0].categoryName", is("한식")))
                .andExpect(jsonPath("$.stores[0].imageUrl", is("https://example.com/store.jpg")))
                .andExpect(jsonPath("$.stores[0].latitude", is(35.1805)))
                .andExpect(jsonPath("$.stores[0].longitude", is(129.0756)))
                .andExpect(jsonPath("$.stores[0].area", is("금천구")))
                .andExpect(jsonPath("$.stores[0].reservationEnabled", is(true)))
                .andExpect(jsonPath("$.stores[0].waitingAvailable", is(false)));
    }

    @Test
    @DisplayName("사각형 모서리 위의 매장은 포함하고 바깥의 매장은 제외한다")
    void listAppliesBounds() throws Exception {
        insertStore("남서쪽 모서리", 35.1696, 129.0656);
        insertStore("북동쪽 모서리", 35.1896, 129.0856);
        insertStore("남쪽 바깥", 35.16959, 129.0756);
        insertStore("동쪽 바깥", 35.1796, 129.08561);
        insertStore("한참 밖", 35.2500, 129.0756);

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", containsInAnyOrder("남서쪽 모서리", "북동쪽 모서리")));
    }

    @Test
    @DisplayName("좌표가 없는 매장은 제외한다")
    void listExcludesStoreWithoutCoordinate() throws Exception {
        insertStore("좌표 있음", 35.1805, 129.0756);
        jdbcTemplate.update("""
                INSERT INTO store (name, category, address,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES ('좌표 없음', 'KOREAN', '서울특별시 중구 세종대로 1', 120, 30, 10)
                """);

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("좌표 있음")));
    }

    @Test
    @DisplayName("거리가 같으면 id 순서로 고정한다")
    void listOrdersByIdWhenDistanceTies() throws Exception {
        insertStore("먼저 등록", 35.1805, 129.0756);
        insertStore("나중 등록", 35.1805, 129.0756);

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("먼저 등록", "나중 등록")));
    }

    @Test
    @DisplayName("페이지를 나눠 가까운 순으로 이어 주고 마지막 페이지는 hasNext가 false다")
    void listPaginates() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertStore("p" + i, 35.1796 + 0.0009 * i, 129.0756);   // 100m 간격
        }

        mockMvc.perform(get(LIST + "&page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("p1", "p2")))
                .andExpect(jsonPath("$.hasNext", is(true)));
        mockMvc.perform(get(LIST + "&page=2&size=2"))
                .andExpect(jsonPath("$.stores[*].name", contains("p5")))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("page와 size를 생략하면 0페이지에 10개씩이고 매장이 없으면 빈 목록이다")
    void listUsesDefaultsAndReturnsEmpty() throws Exception {
        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", empty()))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // 모서리 누락
            "swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=129.0756",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&lat=35.1796&lng=129.0756",
            // 내 위치 누락 (목록은 거리순이라 필수)
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lng=129.0756",
            // 남서쪽이 북동쪽보다 크거나 같음
            "swLat=35.1896&swLng=129.0656&neLat=35.1696&neLng=129.0856&lat=35.1796&lng=129.0756",
            "swLat=35.1696&swLng=129.0856&neLat=35.1896&neLng=129.0656&lat=35.1796&lng=129.0756",
            "swLat=35.1696&swLng=129.0656&neLat=35.1696&neLng=129.0856&lat=35.1796&lng=129.0756",
            // 좌표 범위·형식
            "swLat=-91&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=129.0756",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=181&lat=35.1796&lng=129.0756",
            "swLat=NaN&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=129.0756",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=91&lng=129.0756",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=abc",
            // page·size
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=129.0756&page=-1",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=129.0756&size=0",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.0856&lat=35.1796&lng=129.0756&size=51"
    })
    @DisplayName("목록은 모서리·내 위치가 없거나 잘못되면 400이다")
    void listRejectsInvalidInput(String query) throws Exception {
        mockMvc.perform(get("/api/user/stores/map/bounds?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    @DisplayName("세로가 5km 이하이면 조회하고 넘으면 400이다")
    void listChecksHeightLimit() throws Exception {
        // 위도 0.044도 = 약 4.89km
        mockMvc.perform(get("/api/user/stores/map/bounds?swLat=35.1696&swLng=129.0656&neLat=35.2136&neLng=129.0856" + HERE))
                .andExpect(status().isOk());
        // 위도 0.046도 = 약 5.12km
        mockMvc.perform(get("/api/user/stores/map/bounds?swLat=35.1696&swLng=129.0656&neLat=35.2156&neLng=129.0856" + HERE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    @DisplayName("가로는 위도에 따라 줄어드는 경도 길이로 계산해 5km 이하이면 조회하고 넘으면 400이다")
    void listChecksWidthLimitWithLatitude() throws Exception {
        // 경도 0.054도 x 위도 35.18도 = 약 4.9km
        mockMvc.perform(get("/api/user/stores/map/bounds?swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.1196" + HERE))
                .andExpect(status().isOk());
        // 경도 0.062도 = 약 5.6km
        mockMvc.perform(get("/api/user/stores/map/bounds?swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.1276" + HERE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    // ---- 마커 ----

    @Test
    @DisplayName("마커는 위치와 이름만 내려주고 사각형 안 매장을 id 순으로 전부 준다")
    void markersReturnAllInBoundsById() throws Exception {
        insertStore("첫째", 35.1877, 129.0756);
        insertStore("둘째", 35.1805, 129.0756);
        insertStore("사각형 밖", 35.2500, 129.0756);

        mockMvc.perform(get(MARKERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].name", contains("첫째", "둘째")))
                .andExpect(jsonPath("$.stores[0].id").isNumber())
                .andExpect(jsonPath("$.stores[0].latitude", is(35.1877)))
                .andExpect(jsonPath("$.stores[0].longitude", is(129.0756)))
                .andExpect(jsonPath("$.stores[0].categoryName").doesNotExist())
                .andExpect(jsonPath("$.stores[0].distanceMeters").doesNotExist())
                .andExpect(jsonPath("$.truncated", is(false)));
    }

    @Test
    @DisplayName("마커는 상한 개수까지만 주고 더 있으면 truncated가 true다")
    void markersAreTruncatedAtLimit() throws Exception {
        for (int i = 1; i <= 4; i++) {
            insertStore("m" + i, 35.1796 + 0.0009 * i, 129.0756);
        }

        mockMvc.perform(get(MARKERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(3)))
                .andExpect(jsonPath("$.stores[*].name", contains("m1", "m2", "m3")))
                .andExpect(jsonPath("$.truncated", is(true)));
    }

    @Test
    @DisplayName("마커가 상한 개수와 같으면 truncated는 false이고 매장이 없으면 빈 목록이다")
    void markersAtLimitAreNotTruncated() throws Exception {
        mockMvc.perform(get(MARKERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", empty()))
                .andExpect(jsonPath("$.truncated", is(false)));

        for (int i = 1; i <= 3; i++) {
            insertStore("m" + i, 35.1796 + 0.0009 * i, 129.0756);
        }

        mockMvc.perform(get(MARKERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(3)))
                .andExpect(jsonPath("$.truncated", is(false)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "swLng=129.0656&neLat=35.1896&neLng=129.0856",
            "swLat=35.1896&swLng=129.0656&neLat=35.1696&neLng=129.0856",
            "swLat=35.1696&swLng=129.0856&neLat=35.1896&neLng=129.0656",
            "swLat=-91&swLng=129.0656&neLat=35.1896&neLng=129.0856",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=NaN",
            "swLat=35.1696&swLng=129.0656&neLat=35.2156&neLng=129.0856",
            "swLat=35.1696&swLng=129.0656&neLat=35.1896&neLng=129.1276"
    })
    @DisplayName("마커는 모서리가 없거나 잘못됐거나 5km를 넘으면 400이다")
    void markersRejectInvalidInput(String query) throws Exception {
        mockMvc.perform(get("/api/user/stores/map/bounds/markers?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    private void insertStore(String name, double latitude, double longitude) {
        insertStoreFull(name, "부산 테스트 주소", latitude, longitude, null, null);
    }

    private void insertStoreFull(String name, String address, double latitude, double longitude,
                                 String imageUrl, Long ownerId) {
        jdbcTemplate.update("""
                INSERT INTO store (owner_id, name, category, address, latitude, longitude, image_url,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, ?, 'KOREAN', ?, ?, ?, ?, 120, 30, 10)
                """, ownerId, name, address, latitude, longitude, imageUrl);
    }

    private long insertOwner() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES ('점주', '010-0000-0000', 'OWNER')
                RETURNING id
                """, Long.class);
    }
}
