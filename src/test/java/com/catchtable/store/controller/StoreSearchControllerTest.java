package com.catchtable.store.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Transactional
class StoreSearchControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @SpyBean Clock clock;
    private long owner;

    @BeforeEach
    void setUp() {
        doReturn(Instant.parse("2026-10-28T01:00:00Z")).when(clock).instant();
        owner = jdbc.queryForObject("INSERT INTO member(name, phone, user_type) VALUES ('owner', '000', 'OWNER') RETURNING id",
                Long.class);
    }

    @Test
    void catalogOf66000RowsIsBoundedAndBothFiltersAvoidParameterOverflow() throws Exception {
        jdbc.update("""
                INSERT INTO store (external_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                SELECT 'LARGE-' || n, 'public store ' || n, 'KOREAN', 'address', false, 120, 30, 10
                FROM generate_series(1, 66000) n
                """);
        MvcResult first = mvc.perform(get("/api/user/stores"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(10)))
                .andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        MvcResult second = mvc.perform(get("/api/user/stores").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(10))).andReturn();
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        assertThat(first.getResponse().getContentAsByteArray().length).isLessThan(10_000);
        mvc.perform(get("/api/user/stores").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(50)));
        mvc.perform(get("/api/user/stores").param("partySize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/user/stores").param("date", "2026-10-29").param("time", "12:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(0)));
    }

    @Test
    void allFiltersRunBeforePaginationAndMultipleMatchingTablesDoNotDuplicateStores() throws Exception {
        for (int i = 0; i < 22; i++) store(null);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long id = store(owner);
            expected.add(id);
            table(id, 1, 2, 4, "ACTIVE");
            table(id, 2, 1, 6, "ACTIVE");
            hours(id, "2026-10-29", "10:00", "18:00", null, null, false);
        }
        long inactive = store(owner);
        table(inactive, 1, 1, 4, "INACTIVE");
        hours(inactive, "2026-10-29", "10:00", "18:00", null, null, false);
        long tooSmall = store(owner);
        table(tooSmall, 1, 1, 1, "ACTIVE");
        long tooLarge = store(owner);
        table(tooLarge, 1, 3, 4, "ACTIVE");
        long closed = store(owner);
        table(closed, 1, 1, 4, "ACTIVE");
        long wrongCategory = store(owner);
        jdbc.update("UPDATE store SET category = 'WESTERN' WHERE id = ?", wrongCategory);
        table(wrongCategory, 1, 1, 4, "ACTIVE");
        hours(wrongCategory, "2026-10-29", "10:00", "18:00", null, null, false);
        // 공공 매장에 잘못 남은 테이블·영업시간도 예약 필터의 결과에 나오지 않는다.
        long stalePublic = store(null);
        table(stalePublic, 1, 1, 4, "ACTIVE");
        hours(stalePublic, "2026-10-29", "10:00", "18:00", null, null, false);

        MvcResult first = mvc.perform(get("/api/user/stores").param("category", "KOREAN").param("partySize", "2")
                        .param("date", "2026-10-29").param("time", "12:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        MvcResult second = mvc.perform(get("/api/user/stores").param("category", "KOREAN").param("partySize", "2")
                        .param("date", "2026-10-29").param("time", "12:00").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(false)).andReturn();
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 10));
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(20, 25));
        // 인원 필터만으로도 최소 인원·정원·운영 상태를 DB에서 확인한다.
        MvcResult party = mvc.perform(get("/api/user/stores").param("partySize", "2").param("size", "50"))
                .andExpect(status().isOk()).andReturn();
        assertThat(ids(party)).contains(closed, wrongCategory).doesNotContain(inactive, tooSmall, tooLarge, stalePublic);
    }

    @Test
    void overnightHoursAndBreakAcrossMidnightKeepTheirBoundaries() throws Exception {
        long id = store(owner);
        hours(id, "2026-10-28", "18:00", "03:00", "23:00", "01:00", false);
        assertSearch("2026-10-28", "21:00", List.of(id)); // 브레이크 시작에 종료
        assertSearch("2026-10-28", "22:00", List.of());
        assertSearch("2026-10-29", "00:00", List.of());
        assertSearch("2026-10-29", "01:00", List.of(id)); // 브레이크 종료에 시작·폐점에 종료
        assertSearch("2026-10-29", "01:30", List.of());
        jdbc.update("UPDATE business_hour SET deleted_at = now() WHERE store_id = ?", id);
        assertSearch("2026-10-29", "01:00", List.of());
    }

    @Test
    void daytimeBreakBookingDeadlineAndLastBookableDateAreInclusive() throws Exception {
        long id = store(owner);
        hours(id, "2026-10-29", "10:00", "18:00", "12:00", "13:00", false);
        assertSearch("2026-10-29", "10:00", List.of(id));
        assertSearch("2026-10-29", "11:00", List.of());
        assertSearch("2026-10-29", "13:00", List.of(id));
        assertSearch("2026-10-29", "16:00", List.of(id));
        assertSearch("2026-10-29", "17:00", List.of());
        jdbc.update("UPDATE store SET booking_open_days = 1, booking_deadline_minutes = 60 WHERE id = ?", id);
        assertSearch("2026-10-29", "13:00", List.of(id));
        hours(id, "2026-10-28", "09:00", "20:00", null, null, false);
        assertSearch("2026-10-28", "11:00", List.of(id));
        assertSearch("2026-10-28", "10:59", List.of());
        hours(id, "2026-10-30", "09:00", "20:00", null, null, false);
        assertSearch("2026-10-30", "11:00", List.of());
    }

    @Test
    void invalidSearchAndPaginationInputsReturn400AndPastLastPageIsEmpty() throws Exception {
        for (String[] condition : new String[][]{
                {"page", "-1"}, {"size", "0"}, {"size", "51"},
                {"size", "bad"}, {"partySize", "0"}, {"date", "2026-10-29"}, {"time", "12:00"}}) {
            mvc.perform(get("/api/user/stores").param(condition[0], condition[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mvc.perform(get("/api/user/stores").param("partySize", "2").param("page", "2147483647").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/user/stores").param("page", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    private void assertSearch(String date, String time, List<Long> expected) throws Exception {
        entityManager.clear();
        MvcResult result = mvc.perform(get("/api/user/stores").param("date", date).param("time", time))
                .andExpect(status().isOk()).andReturn();
        assertThat(ids(result)).containsExactlyElementsOf(expected);
    }

    private List<Long> ids(MvcResult result) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode store : mapper.readTree(result.getResponse().getContentAsByteArray()).get("stores")) ids.add(store.get("id").asLong());
        return ids;
    }

    private long store(Long ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'test store', 'KOREAN', 'address', false, 120, 30, 10) RETURNING id
                """, Long.class, ownerId);
    }

    private void table(long id, int number, int min, int capacity, String status) {
        jdbc.update("INSERT INTO store_table(store_id, table_number, min_capacity, capacity, status) VALUES (?, ?, ?, ?, ?)",
                id, number, min, capacity, status);
    }

    private void hours(long id, String date, String opens, String closes, String breakStart, String breakEnd, boolean deleted) {
        jdbc.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time, break_start_time, break_end_time, deleted_at)
                VALUES (?, ?, CAST(? AS TIME), CAST(? AS TIME), CAST(? AS TIME), CAST(? AS TIME), CASE WHEN ? THEN now() END)
                """, id, LocalDate.parse(date).getDayOfWeek().getValue(), opens, closes, breakStart, breakEnd, deleted);
    }
}
