package com.catchtable.store.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Transactional
class PublicStoreBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String TABLE = "{\"tableNumber\":1,\"minCapacity\":1,\"capacity\":4}";
    private static final String POLICY = """
            {"reservationDurationMinutes":120,"reservationSlotMinutes":30,"arrivalGraceMinutes":10,
             "bookingOpenDays":30,"bookingDeadlineMinutes":0}
            """;

    @BeforeEach
    void seedMockCustomerForImmediateReservationApi() {
        // 즉시 확정 API는 현재 헤더 대신 고정 목 고객 1을 사용한다.
        member("CUSTOMER");
        jdbc.update("""
                INSERT INTO member(id, name, phone, user_type) OVERRIDING SYSTEM VALUE
                VALUES (1, 'mock customer', '000', 'CUSTOMER') ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void publicStoreRemainsReadableButCannotBeConfigured() throws Exception {
        long store = store(null);
        mvc.perform(get("/api/user/stores/{id}", store)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationEnabled").value(false));
        for (String resource : new String[]{"tables", "business-hours", "reservation-policy"}) {
            mvc.perform(get("/api/owner/stores/{id}/" + resource, store))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        }
        mvc.perform(post("/api/owner/stores/{id}/tables", store).contentType(MediaType.APPLICATION_JSON).content(TABLE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(put("/api/owner/stores/{id}/business-hours", store).contentType(MediaType.APPLICATION_JSON)
                        .content(hours(tomorrow())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(put("/api/owner/stores/{id}/reservation-policy", store).contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(patch("/api/owner/stores/{id}", store).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"unauthorized change\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(post("/api/owner/stores/{id}/menus", store).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"menu\",\"price\":1000}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        assertThat(count("store_table", store)).isZero();
        assertThat(count("business_hour", store)).isZero();
        assertThat(count("menu", store)).isZero();
    }

    @Test
    void existingSettingsCannotBypassPublicStoreReservationBoundary() throws Exception {
        long store = store(null);
        long customer = member("CUSTOMER");
        LocalDate date = tomorrow();
        long table = jdbc.queryForObject("""
                INSERT INTO store_table (store_id, table_number, min_capacity, capacity, status)
                VALUES (?, 1, 1, 4, 'ACTIVE') RETURNING id
                """, Long.class, store);
        jdbc.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                VALUES (?, ?, '00:00', '23:59')
                """, store, date.getDayOfWeek().getValue());
        long menu = jdbc.queryForObject("INSERT INTO menu(store_id, name, price) VALUES (?, 'menu', 1000) RETURNING id",
                Long.class, store);

        mvc.perform(get("/api/user/stores/{id}", store)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationEnabled").value(false));

        mvc.perform(patch("/api/owner/stores/{id}/tables/{table}", store, table)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":6}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(patch("/api/owner/stores/{id}/menus/{menu}", store, menu)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":2000}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        for (String suffix : new String[]{"", "/holds"}) {
            mvc.perform(post("/api/user/reservations" + suffix).header("X-Member-Id", customer)
                            .contentType(MediaType.APPLICATION_JSON).content(reservation(store, date, "12:00")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        }
        mvc.perform(get("/api/user/stores/{id}/availability", store).param("date", date.toString()).param("partySize", "2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(get("/api/user/stores/{id}/tables", store).param("reservationStartAt", date + "T12:00:00+09:00")
                        .param("partySize", "2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        assertThat(count("reservation", store)).isZero();
        assertThat(jdbc.queryForObject("SELECT capacity FROM store_table WHERE id = ?", Integer.class, table)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id = ?", Integer.class, menu)).isEqualTo(1000);
    }

    @Test
    void ownerWaitingEndpointsHidePublicStoreWith404() throws Exception {
        long store = store(null);
        long owner = member("OWNER");
        for (String suffix : new String[]{"", "/settings", "/subscribe"}) {
            mvc.perform(get("/api/owner/stores/{id}/waitings" + suffix, store).header("X-Member-Id", owner)
                            .param("memberId", Long.toString(owner)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        }
        for (String action : new String[]{"open", "close"}) {
            mvc.perform(post("/api/owner/stores/{id}/waitings/" + action, store).header("X-Member-Id", owner))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        }
        mvc.perform(put("/api/owner/stores/{id}/waitings/settings", store).header("X-Member-Id", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"arrivalLimitMinutes\":10}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(get("/api/owner/stores/{id}/waitings", store).header("X-Member-Id", member("CUSTOMER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
        assertThat(jdbc.queryForObject("SELECT waiting_available FROM store WHERE id = ?", Boolean.class, store)).isFalse();
    }

    @Test
    void publicStoreWaitingStaysClosedEvenWithStaleTrueFlag() throws Exception {
        long store = store(null);
        long customer = member("CUSTOMER");
        for (boolean available : new boolean[]{false, true}) {
            jdbc.update("UPDATE store SET waiting_available = ? WHERE id = ?", available, store);
            mvc.perform(post("/api/user/waitings").header("X-Member-Id", customer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":" + store + ",\"partyCount\":2}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WAITING_CLOSED"));
        }
        assertThat(count("waiting", store)).isZero();
    }

    @Test
    void managedStoreStillSupportsSetupHoldAndImmediateReservation() throws Exception {
        long owner = member("OWNER");
        long store = store(owner);
        long customer = member("CUSTOMER");
        LocalDate date = tomorrow();
        mvc.perform(post("/api/owner/stores/{id}/tables", store).contentType(MediaType.APPLICATION_JSON).content(TABLE))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/owner/stores/{id}/business-hours", store).contentType(MediaType.APPLICATION_JSON)
                        .content(hours(date))).andExpect(status().isOk());
        mvc.perform(put("/api/owner/stores/{id}/reservation-policy", store).contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY)).andExpect(status().isOk());
        mvc.perform(post("/api/user/reservations/holds").header("X-Member-Id", customer)
                        .contentType(MediaType.APPLICATION_JSON).content(reservation(store, date, "12:00")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        mvc.perform(post("/api/user/reservations").header("X-Member-Id", customer)
                        .contentType(MediaType.APPLICATION_JSON).content(reservation(store, date, "14:00")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/owner/stores/{id}/waitings/open", store).header("X-Member-Id", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.open").value(true));
        mvc.perform(get("/api/owner/stores/{id}/waitings", store).header("X-Member-Id", member("OWNER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        assertThat(count("reservation", store)).isEqualTo(2);
    }

    private long member(String type) {
        return jdbc.queryForObject("INSERT INTO member(name, phone, user_type) VALUES ('test', '000', ?) RETURNING id",
                Long.class, type);
    }

    private long store(Long owner) {
        return jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (?, 'test store', 'KOREAN', 'test address', false, 120, 30, 10) RETURNING id
                """, Long.class, owner);
    }

    private long count(String table, long store) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE store_id = ?", Long.class, store);
    }

    private static LocalDate tomorrow() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    }

    private static String hours(LocalDate date) {
        return "{\"businessHours\":[{\"dayOfWeek\":\"" + date.getDayOfWeek()
                + "\",\"openTime\":\"00:00\",\"closingTime\":\"23:59\"}]}";
    }

    private static String reservation(long store, LocalDate date, String time) {
        return "{\"storeId\":" + store + ",\"partySize\":2,\"reservationStartAt\":\"" + date + "T" + time + ":00+09:00\"}";
    }
}
