package com.catchtable.reservation.service;

import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 어댑터 미연결 상태의 기존 동작은 명시적인 FREE 응답과 별도로 검증한다. */
@SpringBootTest(properties = {"reservation.hold-expiry-interval-ms=3600000", "reservation.completion-interval-ms=3600000"})
@AutoConfigureMockMvc
@Import({ReservationDepositConsumerTest.ClockFixture.class, FakeTossPaymentsClient.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ReservationDepositLegacyTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectProvider<ReservationDepositTermsReader> readers;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired ReservationDepositConsumerTest.MutableClock clock;
    Long storeId;

    @BeforeEach
    void setUp() {
        assertThat(readers.getIfAvailable()).isNull();
        clock.set(ReservationDepositConsumerTest.NOW);
        toss.reset();
        jdbc.update("""
                INSERT INTO member (id, email, password, name, phone, user_type)
                OVERRIDING SYSTEM VALUE
                VALUES (1, 'legacy-customer@example.com', 'test', 'test', '010-0000-0000', 'CUSTOMER'),
                       (2, 'legacy-owner@example.com', 'test', 'test', '010-0000-0000', 'OWNER')
                ON CONFLICT (id) DO NOTHING
                """);
        storeId = new PaymentTestData(jdbc).store(2L);
        jdbc.update("INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time) SELECT ?, day, '00:00', '23:59' FROM generate_series(1, 7) day", storeId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noReaderKeepsLegacyDirectAndHoldFlowsWithoutInventingPaidTerms(boolean hold) throws Exception {
        var response = mvc.perform(post("/api/user/reservations" + (hold ? "/holds" : ""))
                        .header("X-Member-Id", 1).contentType("application/json").content("""
                                {"storeId":%d,"reservationStartAt":"%s","partySize":2,
                                 "depositEnabled":true,"amount":10000,"refundPolicy":"STRICT"}
                                """.formatted(storeId, ReservationDepositConsumerTest.START)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value(hold ? "PENDING_PAYMENT" : "CONFIRMED"));
        var row = jdbc.queryForMap("SELECT * FROM reservation WHERE store_id = ?", storeId);
        assertThat(row.get("deposit_amount_snapshot")).isEqualTo(hold ? null : 0L);
        assertThat(row.get("refund_policy_snapshot")).isNull();
        if (hold) response.andExpect(jsonPath("$.remainingSeconds").value(300));
        mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", 1).contentType("application/json")
                .content("{\"holdId\":%d}".formatted(row.get("id"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class, row.get("id"))).isZero();
        assertThat(toss.confirmKeys()).isEmpty();
        assertThat(toss.cancelKeys()).isEmpty();
        assertThat(toss.queries()).isZero();
    }
}
