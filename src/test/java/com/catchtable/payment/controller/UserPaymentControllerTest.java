package com.catchtable.payment.controller;

import com.catchtable.payment.PaymentTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserPaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private PaymentTestData data;
    private Long ownerId;
    private Long customerId;
    private Long storeId;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        ownerId = data.member("OWNER");
        customerId = data.member("CUSTOMER");
        storeId = data.store(ownerId);
    }

    @Test
    void listsOnlyMyPaymentsNewestFirst() throws Exception {
        Long readyHold = data.hold(customerId, storeId, 0);
        Long ready = data.payment(customerId, readyHold, "READY", 0);
        Long refundedHold = data.hold(customerId, storeId, 1);
        Long refunded = data.payment(customerId, refundedHold, "PARTIAL_REFUNDED", 3_000);
        Long other = data.member("CUSTOMER");
        data.payment(other, data.hold(other, storeId, 2), "DONE", 0);

        mockMvc.perform(get("/api/user/payments").header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].paymentId").value(refunded))
                .andExpect(jsonPath("$[0].reservationId").value(refundedHold))
                .andExpect(jsonPath("$[0].holdId").value(refundedHold))
                .andExpect(jsonPath("$[0].orderId").value(data.orderId(refunded)))
                .andExpect(jsonPath("$[0].orderName").value("예약금"))
                .andExpect(jsonPath("$[0].amount").value(PaymentTestData.AMOUNT))
                .andExpect(jsonPath("$[0].status").value("PARTIAL_REFUNDED"))
                .andExpect(jsonPath("$[0].method").value("카드"))
                .andExpect(jsonPath("$[0].approvedAt").exists())
                .andExpect(jsonPath("$[0].canceledAmount").value(3_000))
                .andExpect(jsonPath("$[0].receiptUrl").value("https://receipt"))
                .andExpect(jsonPath("$[1].paymentId").value(ready))
                .andExpect(jsonPath("$[1].reservationId").value(nullValue()))
                .andExpect(jsonPath("$[1].holdId").value(readyHold))
                .andExpect(jsonPath("$[1].approvedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].canceledAmount").value(0));
    }

    @Test
    void returnsMyPaymentDetail() throws Exception {
        Long hold = data.hold(customerId, storeId, 0);
        Long payment = data.payment(customerId, hold, "DONE", 0);

        mockMvc.perform(get("/api/user/payments/{paymentId}", payment).header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment))
                .andExpect(jsonPath("$.reservationId").value(hold))
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void otherCustomersPaymentIsNotFound() throws Exception {
        Long other = data.member("CUSTOMER");
        Long payment = data.payment(other, data.hold(other, storeId, 0), "DONE", 0);

        mockMvc.perform(get("/api/user/payments/{paymentId}", payment).header("X-Member-Id", customerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void ownerIsForbidden() throws Exception {
        Long payment = data.payment(customerId, data.hold(customerId, storeId, 0), "DONE", 0);

        mockMvc.perform(get("/api/user/payments").header("X-Member-Id", ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
        mockMvc.perform(get("/api/user/payments/{paymentId}", payment).header("X-Member-Id", ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
    }

    @Test
    void missingMemberHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/user/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/user/payments/{paymentId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
