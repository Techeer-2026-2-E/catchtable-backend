package com.catchtable.reservation.service;

import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 점주 API로 저장한 매장 값이 운영 리더를 거쳐 예약 스냅샷까지 이어지는지 확인한다. */
@SpringBootTest(properties = {"reservation.hold-expiry-interval-ms=3600000", "reservation.completion-interval-ms=3600000"})
@AutoConfigureMockMvc
@Import({ReservationDepositConsumerTest.ClockFixture.class, FakeTossPaymentsClient.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class StoreDepositPolicyFlowTest {

    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;
    @Autowired MockMvc mvc;
    @Autowired ReservationDepositConsumerTest.MutableClock clock;
    Long storeId;

    @BeforeEach
    void setUp() {
        clock.set(ReservationDepositConsumerTest.NOW);
        jdbc.update("""
                INSERT INTO member (id, email, password, name, phone, user_type)
                OVERRIDING SYSTEM VALUE
                VALUES (1, 'flow-customer@example.com', 'test', 'test', '010-0000-0000', 'CUSTOMER'),
                       (2, 'flow-owner@example.com', 'test', 'test', '010-0000-0000', 'OWNER')
                ON CONFLICT (id) DO NOTHING
                """);
        storeId = new PaymentTestData(jdbc).store(2L);
        jdbc.update("INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time) SELECT ?, day, '00:00', '23:59' FROM generate_series(1, 7) day", storeId);
    }

    ResultActions putPolicy(String body) throws Exception {
        return mvc.perform(put("/api/owner/stores/{id}/deposit-policy", storeId)
                .contentType("application/json").content(body));
    }

    ResultActions create(String path) throws Exception {
        return mvc.perform(post("/api/user/reservations" + path).header("X-Member-Id", 1)
                .contentType("application/json").content("""
                        {"storeId":%d,"reservationStartAt":"%s","partySize":2}
                        """.formatted(storeId, ReservationDepositConsumerTest.START)));
    }

    @Test
    void newStoreIsFreeByDefault() throws Exception {
        mvc.perform(get("/api/owner/stores/{id}/deposit-policy", storeId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.depositEnabled").value(false))
                .andExpect(jsonPath("$.depositAmount").value(0))
                .andExpect(jsonPath("$.refundPolicy").isEmpty());
        create("").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void savedTermsBecomeHoldSnapshotAndDirectCreateIsRejected() throws Exception {
        putPolicy("{\"depositEnabled\":true,\"depositAmount\":10000,\"refundPolicy\":\"STRICT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.depositAmount").value(10000));
        create("").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_PAYMENT_REQUIRED"));
        create("/holds").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        var row = jdbc.queryForMap("SELECT * FROM reservation WHERE store_id = ?", storeId);
        assertThat(row.get("deposit_amount_snapshot")).isEqualTo(10000L);
        assertThat(row.get("refund_policy_snapshot")).isEqualTo("STRICT");
    }

    @Test
    void changingPolicyKeepsExistingSnapshotAndDisablingClearsValues() throws Exception {
        putPolicy("{\"depositEnabled\":true,\"depositAmount\":10000,\"refundPolicy\":\"STRICT\"}").andExpect(status().isOk());
        create("/holds").andExpect(status().isCreated());
        putPolicy("{\"depositEnabled\":false,\"depositAmount\":5000,\"refundPolicy\":\"FLEXIBLE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depositEnabled").value(false))
                .andExpect(jsonPath("$.depositAmount").value(0))
                .andExpect(jsonPath("$.refundPolicy").isEmpty());
        var row = jdbc.queryForMap("SELECT * FROM reservation WHERE store_id = ?", storeId);
        assertThat(row.get("deposit_amount_snapshot")).isEqualTo(10000L);
        assertThat(row.get("refund_policy_snapshot")).isEqualTo("STRICT");
        entityManager.flush(); // 테스트 트랜잭션 안의 변경을 JDBC 조회 전에 DB로 내보낸다
        var store = jdbc.queryForMap("SELECT deposit_enabled, deposit_amount, refund_policy FROM store WHERE id = ?", storeId);
        assertThat(store.get("deposit_enabled")).isEqualTo(false);
        assertThat(store.get("deposit_amount")).isEqualTo(0L);
        assertThat(store.get("refund_policy")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"depositEnabled\":true,\"depositAmount\":0,\"refundPolicy\":\"STRICT\"}",
            "{\"depositEnabled\":true,\"depositAmount\":-1,\"refundPolicy\":\"STRICT\"}",
            "{\"depositEnabled\":true,\"refundPolicy\":\"STRICT\"}",
            "{\"depositEnabled\":true,\"depositAmount\":10000}",
            "{\"depositEnabled\":true,\"depositAmount\":10000,\"refundPolicy\":\"NOPE\"}",
            "{\"depositAmount\":10000,\"refundPolicy\":\"STRICT\"}"
    })
    void invalidRequestsAreBadRequest(String body) throws Exception {
        putPolicy(body).andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrUnmanagedStoreIsNotFound() throws Exception {
        mvc.perform(put("/api/owner/stores/{id}/deposit-policy", 999999999L).contentType("application/json")
                .content("{\"depositEnabled\":false}")).andExpect(status().isNotFound());
        Long publicStoreId = new PaymentTestData(jdbc).store(null);
        mvc.perform(put("/api/owner/stores/{id}/deposit-policy", publicStoreId).contentType("application/json")
                .content("{\"depositEnabled\":false}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/owner/stores/{id}/deposit-policy", publicStoreId)).andExpect(status().isNotFound());
    }

    @Test
    void databaseRejectsInconsistentValues() {
        assertThatThrownBy(() -> jdbc.update("UPDATE store SET deposit_amount = 100 WHERE id = ?", storeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
