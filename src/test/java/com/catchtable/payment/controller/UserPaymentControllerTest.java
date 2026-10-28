package com.catchtable.payment.controller;

import com.catchtable.payment.PaymentTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].paymentId").value(refunded))
                .andExpect(jsonPath("$.payments[0].reservationId").value(refundedHold))
                .andExpect(jsonPath("$.payments[0].holdId").value(refundedHold))
                .andExpect(jsonPath("$.payments[0].orderId").value(data.orderId(refunded)))
                .andExpect(jsonPath("$.payments[0].orderName").value("예약금"))
                .andExpect(jsonPath("$.payments[0].amount").value(PaymentTestData.AMOUNT))
                .andExpect(jsonPath("$.payments[0].status").value("PARTIAL_REFUNDED"))
                .andExpect(jsonPath("$.payments[0].method").value("카드"))
                .andExpect(jsonPath("$.payments[0].approvedAt").exists())
                .andExpect(jsonPath("$.payments[0].canceledAmount").value(3_000))
                .andExpect(jsonPath("$.payments[0].receiptUrl").value("https://receipt"))
                .andExpect(jsonPath("$.payments[1].paymentId").value(ready))
                .andExpect(jsonPath("$.payments[1].reservationId").value(nullValue()))
                .andExpect(jsonPath("$.payments[1].holdId").value(readyHold))
                .andExpect(jsonPath("$.payments[1].approvedAt").value(nullValue()))
                .andExpect(jsonPath("$.payments[1].canceledAmount").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(false));
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
    @DisplayName("결제 내역은 page·size로 나누고 다음 페이지 여부를 반환한다")
    void listsPaymentsByPage() throws Exception {
        Long older = data.payment(customerId, data.hold(customerId, storeId, 0), "DONE", 0);
        Long newer = data.payment(customerId, data.hold(customerId, storeId, 1), "DONE", 0);

        mockMvc.perform(get("/api/user/payments").param("page", "0").param("size", "1")
                        .header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].paymentId").value(newer))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get("/api/user/payments").param("page", "1").param("size", "1")
                        .header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].paymentId").value(older))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get("/api/user/payments").param("size", "51").header("X-Member-Id", customerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("결제 상세는 환불 이력을 요청순으로 포함한다")
    void detailIncludesRefunds() throws Exception {
        Long payment = data.payment(customerId, data.hold(customerId, storeId, 0), "PARTIAL_REFUNDED", 5_000);
        Long first = data.completedRefund(payment, 2_000, 2_000, "일정 변경");
        Long second = data.completedRefund(payment, 3_000, 5_000, "인원 변경");

        mockMvc.perform(get("/api/user/payments/{paymentId}", payment).header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment))
                .andExpect(jsonPath("$.method").value("카드"))
                .andExpect(jsonPath("$.approvedAt").exists())
                .andExpect(jsonPath("$.receiptUrl").value("https://receipt"))
                .andExpect(jsonPath("$.canceledAmount").value(5_000))
                .andExpect(jsonPath("$.refunds.length()").value(2))
                .andExpect(jsonPath("$.refunds[0].refundId").value(first))
                .andExpect(jsonPath("$.refunds[0].refundAmount").value(2_000))
                .andExpect(jsonPath("$.refunds[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.refunds[0].cancelReason").value("일정 변경"))
                .andExpect(jsonPath("$.refunds[0].refundedAt").exists())
                .andExpect(jsonPath("$.refunds[1].refundId").value(second));
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
