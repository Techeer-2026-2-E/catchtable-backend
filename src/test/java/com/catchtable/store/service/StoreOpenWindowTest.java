package com.catchtable.store.service;

import com.catchtable.payment.ReconcileTestClock;
import com.catchtable.store.dto.BusinessHourRequest;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기준 시각 2031-03-03(월) 10:00 고정
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ReconcileTestClock.Config.class)
class StoreOpenWindowTest {

    private static final LocalDate TODAY = LocalDate.parse("2031-03-03");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @Autowired ReconcileTestClock clock;
    @Autowired BusinessHourService businessHourService;
    @Autowired StoreOpenWindowService openWindowService;

    @Test
    @DisplayName("이미 계산된 날짜도 영업시간을 수정하면 바로 검색 결과에 반영된다")
    void businessHourChangeIsReflectedImmediately() throws Exception {
        clock.set(OffsetDateTime.parse("2031-03-03T10:00:00+09:00"));
        long store = ownedStore();
        replaceHours(store, "10:00", "22:00");
        assertThat(search("2031-03-04", "12:00")).contains(store);
        assertThat(search("2031-03-04", "19:00")).contains(store);

        replaceHours(store, "10:00", "15:00");

        assertThat(search("2031-03-04", "12:00")).contains(store);
        assertThat(search("2031-03-04", "19:00")).doesNotContain(store);
    }

    @Test
    @DisplayName("날짜가 지나면 하루 갱신이 지난 날짜를 지우고 새 날짜를 채운다")
    void dailyRefreshMovesTheRange() {
        clock.set(OffsetDateTime.parse("2031-03-03T10:00:00+09:00"));
        long store = ownedStore();
        replaceHours(store, "10:00", "22:00");
        openWindowService.refreshDaily();
        assertThat(windowDates(store)).first().isEqualTo(TODAY.minusDays(1));
        assertThat(windowDates(store)).last().isEqualTo(TODAY.plusDays(StoreOpenWindowService.PRECOMPUTE_DAYS));

        clock.set(OffsetDateTime.parse("2031-03-05T00:10:00+09:00"));
        openWindowService.refreshDaily();

        assertThat(windowDates(store)).first().isEqualTo(TODAY.plusDays(1));
        assertThat(windowDates(store)).last().isEqualTo(TODAY.plusDays(StoreOpenWindowService.PRECOMPUTE_DAYS + 2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_open_window_date WHERE business_date < ?",
                Integer.class, TODAY.plusDays(1))).isZero();
    }

    private long ownedStore() {
        long owner = jdbc.queryForObject(
                "INSERT INTO member(name, phone, user_type) VALUES ('owner', '000', 'OWNER') RETURNING id", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'window store', 'KOREAN', 'address', false, 120, 30, 10) RETURNING id
                """, Long.class, owner);
    }

    private void replaceHours(long store, String opens, String closes) {
        List<BusinessHourRequest> week = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            week.add(new BusinessHourRequest(day, LocalTime.parse(opens), LocalTime.parse(closes), null, null));
        }
        businessHourService.replaceBusinessHours(store, new BusinessHourUpdateRequest(week));
        entityManager.flush();
        entityManager.clear();
    }

    private List<Long> search(String date, String time) throws Exception {
        byte[] body = mvc.perform(get("/api/user/stores").param("date", date).param("time", time).param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : mapper.readTree(body).get("stores")) ids.add(node.get("id").asLong());
        return ids;
    }

    private List<LocalDate> windowDates(long store) {
        return jdbc.queryForList("SELECT business_date FROM store_open_window WHERE store_id = ? ORDER BY business_date",
                LocalDate.class, store);
    }
}
