package com.catchtable.reservation.service;

import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.client.TossPaymentsException;
import com.catchtable.payment.dto.PaymentConfirmRequest;
import com.catchtable.payment.dto.PaymentPrepareResponse;
import com.catchtable.reservation.dto.ReservationHoldResponse;
import com.catchtable.store.service.StoreDepositTermsReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 매장 설정과 운영 리더부터 결제·환불까지 연결하고, 외부 PG 응답만 fake로 대체한다. */
@SpringBootTest(properties = {"reservation.hold-expiry-interval-ms=3600000", "reservation.completion-interval-ms=3600000"})
@AutoConfigureMockMvc
@Import({ReservationDepositConsumerTest.ClockFixture.class, FakeTossPaymentsClient.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreDepositPaymentFlowTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ReservationDepositConsumerTest.MutableClock clock;
    @Autowired ReservationDepositTermsReader termsReader;
    @SpyBean FakeTossPaymentsClient toss;
    PaymentTestData data;
    Long memberId, storeId;
    final List<Long> mockMembers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertThat(termsReader).isInstanceOf(StoreDepositTermsReader.class);
        clock.set(ReservationDepositConsumerTest.NOW);
        toss.reset();
        for (long id = 1; id <= 2; id++) {
            jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('member', 'id'))", Long.class);
            if (jdbc.update("""
                    INSERT INTO member (id, email, password, name, phone, user_type)
                    OVERRIDING SYSTEM VALUE VALUES (?, ?, 'test', 'test', '010-0000-0000', ?)
                    ON CONFLICT (id) DO NOTHING
                    """, id, "store-payment-" + id + "@example.com", id == 1 ? "CUSTOMER" : "OWNER") == 1) {
                mockMembers.add(id);
            }
        }
        data = new PaymentTestData(jdbc);
        memberId = data.member("CUSTOMER");
        storeId = data.store(2L);
        jdbc.update("""
                INSERT INTO business_hour (store_id, day_of_week, open_time, closing_time)
                SELECT ?, day, '00:00', '23:59' FROM generate_series(1, 7) day
                """, storeId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM reservation_refund_request WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM business_hour WHERE store_id = ?", storeId);
        data.deleteStore(storeId, List.of(memberId));
        mockMembers.forEach(id -> jdbc.update("DELETE FROM member WHERE id = ?", id));
        mockMembers.clear();
        clock.set(ReservationDepositConsumerTest.NOW);
    }

    @Test
    void storedPolicySurvivesSettingsChangesThroughPrepareApprovalAndPartialRefund() throws Exception {
        policy("{\"depositEnabled\":true,\"depositAmount\":10001,\"refundPolicy\":\"STANDARD\"}");
        var hold = hold();
        policy("{\"depositEnabled\":true,\"depositAmount\":30003,\"refundPolicy\":\"STRICT\"}");
        var prepared = prepare(hold.holdId(), 201);
        assertThat(prepared.amount()).isEqualTo(10001);
        clock.set(ReservationDepositConsumerTest.NOW.plusMinutes(2));
        var repeated = prepare(hold.holdId(), 200);
        assertThat(repeated.paymentId()).isEqualTo(prepared.paymentId());
        assertThat(repeated.orderId()).isEqualTo(prepared.orderId());
        assertThat(repeated.amount()).isEqualTo(10001);
        assertThat(repeated.expiresAt()).isAtSameInstantAs(hold.expiresAt());
        assertThat(repeated.remainingSeconds()).isEqualTo(180);
        confirm(prepared).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        confirm(prepared).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(toss.confirmKeys()).hasSize(1);

        mvc.perform(patch("/api/owner/reservations/{id}", hold.holdId()).header("X-Member-Id", 2)
                .contentType("application/json")
                .content("{\"reservationStartAt\":\"2030-01-20T15:00:00Z\",\"partySize\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.partySize").value(3));
        preview(hold.holdId()).andExpect(jsonPath("$.policyType").value("STANDARD"))
                .andExpect(jsonPath("$.paidAmount").value(10001))
                .andExpect(jsonPath("$.refundRate").value(50))
                .andExpect(jsonPath("$.refundableAmount").value(5000));
        mvc.perform(post("/api/user/payments/{id}/cancel", prepared.paymentId()).header("X-Member-Id", memberId)
                .header("Idempotency-Key", "store-partial").contentType("application/json")
                .content("{\"cancelReason\":\"일부 환불\",\"cancelAmount\":1000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(1000));
        preview(hold.holdId()).andExpect(jsonPath("$.alreadyRefundedAmount").value(1000))
                .andExpect(jsonPath("$.refundableAmount").value(4000));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/user/reservations/{id}/cancel", hold.holdId()).header("X-Member-Id", memberId)
                    .header("Idempotency-Key", "store-customer-cancel"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment WHERE id = ?", Long.class, prepared.paymentId()))
                .isEqualTo(5000);
        assertThat(jdbc.queryForMap("SELECT deposit_amount_snapshot, refund_policy_snapshot FROM reservation WHERE id = ?", hold.holdId()))
                .containsEntry("deposit_amount_snapshot", 10001L).containsEntry("refund_policy_snapshot", "STANDARD");
        assertThat(toss.cancelExecutions()).isEqualTo(2);
    }

    @Test
    void disablingStoreDepositsPreservesOwnerFullRefundForExistingPayment() throws Exception {
        policy("{\"depositEnabled\":true,\"depositAmount\":10001,\"refundPolicy\":\"STRICT\"}");
        var hold = hold();
        var prepared = prepare(hold.holdId(), 201);
        confirm(prepared).andExpect(status().isOk());
        policy("{\"depositEnabled\":false}");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/owner/reservations/{id}/cancel", hold.holdId())
                    .header("Idempotency-Key", "store-owner-cancel").contentType("application/json")
                    .content("{\"reason\":\"매장 사정\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        assertThat(jdbc.queryForMap("SELECT status, refunded_amount FROM payment WHERE id = ?", prepared.paymentId()))
                .containsEntry("status", "REFUNDED").containsEntry("refunded_amount", 10001L);
        assertThat(toss.cancelExecutions()).isEqualTo(1);
        mvc.perform(post("/api/user/reservations").contentType("application/json").content(holdBody()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedApprovalReleasesSlotAndUnknownApprovalRetainsItUntilReconciled(boolean unknown) throws Exception {
        policy("{\"depositEnabled\":true,\"depositAmount\":10001,\"refundPolicy\":\"STANDARD\"}");
        var hold = hold();
        var prepared = prepare(hold.holdId(), 201);
        toss.failNext(unknown ? FakeTossPaymentsClient.timeout() : FakeTossPaymentsClient.rejected());
        confirm(prepared).andExpect(status().is(unknown ? 409 : 200));
        if (unknown) {
            clock.set(ReservationDepositConsumerTest.NOW.plusMinutes(6));
            holdRequest().andExpect(status().isConflict());
            doThrow(new TossPaymentsException(404, "NOT_FOUND_MERCHANT", "gsk orderId 조회 미지원"))
                    .when(toss).getByOrderId(prepared.orderId());
            toss.query(prepared.orderId(), "store-policy-key", "DONE", prepared.amount());
            mvc.perform(post("/api/user/payments/{id}/sync", prepared.paymentId()).header("X-Member-Id", memberId))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
            assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, hold.holdId()))
                    .isEqualTo("CONFIRMED");
            verify(toss).getByPaymentKey("store-policy-key");
            verify(toss, never()).getByOrderId(anyString());
            assertThat(toss.confirmKeys()).hasSize(1);
        } else {
            assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, hold.holdId()))
                    .isEqualTo("CANCELLED");
            var next = hold();
            assertThat(prepare(next.holdId(), 201).amount()).isEqualTo(10001);
        }
    }

    @Test
    void unrelatedApprovalDoesNotSupplyAReconciliationKey() throws Exception {
        policy("{\"depositEnabled\":true,\"depositAmount\":10001,\"refundPolicy\":\"STANDARD\"}");
        var hold = hold();
        var prepared = prepare(hold.holdId(), 201);
        toss.failNext(FakeTossPaymentsClient.timeout());
        confirm(prepared).andExpect(status().isConflict());
        jdbc.update("UPDATE reservation SET approval_order_id = 'another-order' WHERE id = ?", hold.holdId());
        doThrow(new TossPaymentsException(404, "NOT_FOUND_PAYMENT", "원장 미확인"))
                .when(toss).getByOrderId(prepared.orderId());

        mvc.perform(post("/api/user/payments/{id}/sync", prepared.paymentId()).header("X-Member-Id", memberId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TIMEOUT"));

        verify(toss).getByOrderId(prepared.orderId());
        verify(toss, never()).getByPaymentKey(anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, hold.holdId()))
                .isEqualTo("PENDING_PAYMENT");
    }

    private void policy(String body) throws Exception {
        mvc.perform(put("/api/owner/stores/{id}/deposit-policy", storeId)
                .contentType("application/json").content(body)).andExpect(status().isOk());
    }

    private String holdBody() {
        return """
                {"storeId":%d,"reservationStartAt":"%s","partySize":4}
                """.formatted(storeId, ReservationDepositConsumerTest.START);
    }

    private ResultActions holdRequest() throws Exception {
        return mvc.perform(post("/api/user/reservations/holds").header("X-Member-Id", memberId)
                .contentType("application/json").content(holdBody()));
    }

    private ReservationHoldResponse hold() throws Exception {
        return json.readValue(holdRequest().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                ReservationHoldResponse.class);
    }

    private PaymentPrepareResponse prepare(Long holdId, int expected) throws Exception {
        return json.readValue(mvc.perform(post("/api/user/payments/prepare").header("X-Member-Id", memberId)
                        .contentType("application/json").content("{\"holdId\":%d}".formatted(holdId)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), PaymentPrepareResponse.class);
    }

    private ResultActions confirm(PaymentPrepareResponse prepared) throws Exception {
        return mvc.perform(post("/api/user/payments/confirm").header("X-Member-Id", memberId)
                .contentType("application/json").content(json.writeValueAsString(
                        new PaymentConfirmRequest(prepared.orderId(), "store-policy-key", prepared.amount()))));
    }

    private ResultActions preview(Long holdId) throws Exception {
        return mvc.perform(get("/api/user/reservations/{id}/refund-preview", holdId).header("X-Member-Id", memberId))
                .andExpect(status().isOk());
    }
}
