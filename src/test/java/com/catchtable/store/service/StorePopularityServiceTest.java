package com.catchtable.store.service;

import com.catchtable.store.search.StoreSearchOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기준 시각 고정. 부산시청 근처 매장으로 주변 매장 정렬 확인
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StorePopularityServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-06-01T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    private static final double LAT = 35.1796;
    private static final double LNG = 129.0756;

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("최근 30일 확정·완료 예약과 착석 웨이팅만 점수에 들어간다")
    void countsOnlyValidRecentActivity() {
        long store = store("인기식당", LAT, LNG);
        long table = table(store);
        long member = member();
        reservation(store, table, member, "CONFIRMED", NOW.minusDays(1), 0);
        reservation(store, table, member, "COMPLETED", NOW.minusDays(29), 1);
        reservation(store, table, member, "CANCELLED", NOW.minusDays(1), 2);
        reservation(store, table, member, "NO_SHOW", NOW.minusDays(1), 3);
        reservation(store, table, member, "CONFIRMED", NOW.minusDays(31), 4);
        waiting(store, member, "SEATED", 1, 1);
        waiting(store, member, "CANCELED", 1, 2);
        waiting(store, member, "SEATED", 31, 3);

        service("postgres").refresh();

        assertThat(jdbc.queryForMap(
                "SELECT score, reservation_count, waiting_count FROM store_popularity WHERE store_id = ?", store))
                .containsEntry("score", 3).containsEntry("reservation_count", 2).containsEntry("waiting_count", 1);
    }

    @Test
    @DisplayName("opensearch면 점수가 바뀐 매장만 Outbox에 기록하고, 사라진 점수도 기록한다")
    void recordsOnlyChangedStores() {
        long changed = store("바뀐식당", LAT, LNG);
        long same = store("그대로식당", LAT, LNG);
        long gone = store("식은식당", LAT, LNG);
        long member = member();
        reservation(changed, table(changed), member, "CONFIRMED", NOW.minusDays(1), 0);
        reservation(same, table(same), member, "CONFIRMED", NOW.minusDays(1), 0);
        popularity(same, 1);
        popularity(gone, 5);

        service("opensearch").refresh();

        assertThat(outboxCount(changed)).isEqualTo(1);
        assertThat(outboxCount(same)).isZero();
        assertThat(outboxCount(gone)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_popularity WHERE store_id = ?", Integer.class, gone))
                .isZero();
    }

    @Test
    @DisplayName("postgres면 점수가 바뀌어도 Outbox에 기록하지 않는다")
    void postgresRecordsNothing() {
        long store = store("인기식당", LAT, LNG);
        reservation(store, table(store), member(), "CONFIRMED", NOW.minusDays(1), 0);

        service("postgres").refresh();

        assertThat(outboxCount(store)).isZero();
    }

    @Test
    @DisplayName("sort=POPULAR는 점수 높은 순(같으면 id 순, 점수 없으면 0), 없으면 가까운 순")
    void homeSort() throws Exception {
        long far = store("먼인기", LAT + 0.0081, LNG);
        long near = store("가까운무점수", LAT + 0.0009, LNG);
        long mid = store("중간인기", LAT, LNG + 0.0055);
        popularity(far, 10);
        popularity(mid, 10);
        String home = "/api/user/stores/home?lat=" + LAT + "&lng=" + LNG;

        mvc.perform(get(home + "&sort=POPULAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[*].id", contains((int) far, (int) mid, (int) near)));
        mvc.perform(get(home))
                .andExpect(jsonPath("$.stores[*].id", contains((int) near, (int) mid, (int) far)));
        mvc.perform(get(home + "&sort=WRONG"))
                .andExpect(status().isBadRequest());
    }

    private StorePopularityService service(String engine) {
        return new StorePopularityService(jdbc, new StoreSearchOutbox(jdbc, CLOCK, engine), CLOCK);
    }

    private long store(String name, double lat, double lng) {
        return jdbc.queryForObject("""
                INSERT INTO store (name, category, address, latitude, longitude,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'KOREAN', '부산 테스트 주소', ?, ?, 120, 30, 10) RETURNING id
                """, Long.class, name, lat, lng);
    }

    private long table(long store) {
        return jdbc.queryForObject("INSERT INTO store_table (store_id, table_number, capacity) VALUES (?, 1, 4) RETURNING id",
                Long.class, store);
    }

    private long member() {
        return jdbc.queryForObject(
                "INSERT INTO member(name, phone, user_type) VALUES ('고객', '000', 'CUSTOMER') RETURNING id", Long.class);
    }

    // 테이블 시간 겹침 제약 회피용 slot
    private void reservation(long store, long table, long member, String status, OffsetDateTime requestedAt, int slot) {
        OffsetDateTime start = NOW.plusDays(1).plusHours(slot * 3L);
        jdbc.update("""
                INSERT INTO reservation (member_id, store_id, table_id, reservation_start_at, reservation_end_at,
                                         party_size, status, requested_at, confirmed_at)
                VALUES (?, ?, ?, ?, ?, 2, ?, ?, ?)
                """, member, store, table, start, start.plusHours(2), status, requestedAt, requestedAt);
    }

    private void waiting(long store, long member, String status, int daysAgo, int number) {
        jdbc.update("""
                INSERT INTO waiting (member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                                     call_count, recall_count, created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?, ?, 0, 0, now(), now())
                """, member, store, NOW.toLocalDate().minusDays(daysAgo), number, number, status);
    }

    private void popularity(long store, int score) {
        jdbc.update("""
                INSERT INTO store_popularity (store_id, score, reservation_count, waiting_count, calculated_at)
                VALUES (?, ?, ?, 0, now())
                """, store, score, score);
    }

    private int outboxCount(long store) {
        return jdbc.queryForObject("SELECT count(*) FROM store_search_outbox WHERE store_id = ?", Integer.class, store);
    }
}
