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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL에 Flyway 스키마로 실행. 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OwnerSalesControllerTest {

    private static final long AMOUNT = PaymentTestData.AMOUNT;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private PaymentTestData data;
    private Long ownerId;
    private Long customerId;
    private Long storeId;
    private int slot;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        ownerId = data.member("OWNER");
        customerId = data.member("CUSTOMER");
        storeId = data.store(ownerId);
    }

    // 결제 1건을 만들고 승인 시각을 지정한다 (예약별 시간을 분리한다)
    private Long payment(Long targetStoreId, String status, long refundedAmount, String approvedAt) {
        Long paymentId = data.payment(customerId, data.hold(customerId, targetStoreId, slot++), status, refundedAmount);
        if (approvedAt != null) {
            jdbc.update("UPDATE payment SET approved_at = ? WHERE id = ?", OffsetDateTime.parse(approvedAt), paymentId);
        }
        return paymentId;
    }

    private ResultActions sales(Long memberId, Long targetStoreId, String from, String to) throws Exception {
        return mockMvc.perform(get("/api/owner/stores/{storeId}/sales", targetStoreId)
                .header("X-Member-Id", memberId)
                .queryParam("from", from)
                .queryParam("to", to));
    }

    @Test
    @DisplayName("성공 결제만 승인액에 넣고 누적 환불액을 차감한다")
    void sumsApprovedPaymentsAndSubtractsRefunds() throws Exception {
        payment(storeId, "DONE", 0, "2030-01-19T10:00:00+09:00");
        payment(storeId, "PARTIAL_REFUNDED", 3_000, "2030-01-19T11:00:00+09:00");
        payment(storeId, "REFUNDED", AMOUNT, "2030-01-19T12:00:00+09:00");
        // 실패·취소·시간초과·대기 결제는 매출에서 제외한다
        payment(storeId, "READY", 0, null);
        payment(storeId, "FAILED", 0, null);
        payment(storeId, "CANCELED", 0, null);
        payment(storeId, "TIMEOUT", 0, null);

        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2030-01-19"))
                .andExpect(jsonPath("$.to").value("2030-01-19"))
                .andExpect(jsonPath("$.approvedAmount").value(3 * AMOUNT))
                .andExpect(jsonPath("$.refundedAmount").value(AMOUNT + 3_000))
                .andExpect(jsonPath("$.netAmount").value(3 * AMOUNT - AMOUNT - 3_000))
                .andExpect(jsonPath("$.notice", containsString("테스트")));
    }

    @Test
    @DisplayName("기간은 서울 기준 from 00:00 이상, to 다음 날 00:00 미만이다")
    void periodBoundariesUseSeoulTime() throws Exception {
        payment(storeId, "DONE", 0, "2030-01-18T14:59:59Z");   // 서울 1/18 23:59:59 → 제외
        payment(storeId, "DONE", 0, "2030-01-18T15:00:00Z");   // 서울 1/19 00:00:00 → 포함
        payment(storeId, "DONE", 0, "2030-01-19T14:59:59Z");   // 서울 1/19 23:59:59 → 포함
        payment(storeId, "DONE", 0, "2030-01-19T15:00:00Z");   // 서울 1/20 00:00:00 → 제외

        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(2 * AMOUNT));
    }

    @Test
    @DisplayName("승인 시각이 기간 밖인 결제의 환불은 이 기간에 잡히지 않는다")
    void refundOfPaymentApprovedInAnotherPeriodIsNotCounted() throws Exception {
        payment(storeId, "PARTIAL_REFUNDED", 4_000, "2030-01-19T10:00:00+09:00");

        sales(ownerId, storeId, "2030-01-20", "2030-01-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(0))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.netAmount").value(0));

        sales(ownerId, storeId, "2030-01-01", "2030-01-19")
                .andExpect(jsonPath("$.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.refundedAmount").value(4_000))
                .andExpect(jsonPath("$.netAmount").value(AMOUNT - 4_000));
    }

    @Test
    @DisplayName("나중에 환불되면 승인일이 속한 과거 기간의 누적 환불액이 바뀐다")
    void laterRefundUpdatesTheOriginalApprovalPeriod() throws Exception {
        Long paymentId = payment(storeId, "DONE", 0, "2030-01-19T10:00:00+09:00");

        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.netAmount").value(AMOUNT));

        Long refundId = data.completedRefund(paymentId, 4_000, 4_000, "고객 취소");
        OffsetDateTime refundedAt = OffsetDateTime.parse("2030-02-01T10:00:00+09:00");
        jdbc.update("UPDATE payment_refund SET requested_at = ?, completed_at = ? WHERE id = ?",
                refundedAt, refundedAt, refundId);
        jdbc.update("UPDATE payment SET status = 'PARTIAL_REFUNDED', refunded_amount = 4000 WHERE id = ?",
                paymentId);

        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.refundedAmount").value(4_000))
                .andExpect(jsonPath("$.netAmount").value(AMOUNT - 4_000));
        sales(ownerId, storeId, "2030-02-01", "2030-02-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(0))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.netAmount").value(0));
    }

    @Test
    @DisplayName("예약 미확정으로 환불 필요가 표시된 DONE도 승인액에 포함한다")
    void includesRefundRequiredApprovalWithoutConfirmedReservation() throws Exception {
        Long paymentId = payment(storeId, "DONE", 0, "2030-01-19T10:00:00+09:00");
        jdbc.update("UPDATE reservation SET status = 'EXPIRED' WHERE id = "
                + "(SELECT reservation_id FROM payment WHERE id = ?)", paymentId);
        jdbc.update("UPDATE payment SET refund_required_at = ?, refund_required_reason = ? WHERE id = ?",
                OffsetDateTime.parse("2030-01-19T10:01:00+09:00"), "선점 만료 후 승인 확인", paymentId);

        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.netAmount").value(AMOUNT));
    }

    @Test
    @DisplayName("다른 매장의 결제는 합계에 들어가지 않는다")
    void excludesOtherStorePayments() throws Exception {
        Long otherStoreId = data.store(data.member("OWNER"));
        payment(storeId, "DONE", 0, "2030-01-19T10:00:00+09:00");
        payment(otherStoreId, "DONE", 0, "2030-01-19T10:00:00+09:00");

        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(jsonPath("$.approvedAmount").value(AMOUNT));
    }

    @Test
    @DisplayName("결제가 없으면 모든 합계가 0이다")
    void emptyPeriodIsZero() throws Exception {
        sales(ownerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(0))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.netAmount").value(0));
    }

    @Test
    @DisplayName("남의 매장·없는 매장·점주 없는 공공 매장은 404다")
    void rejectsStoresTheOwnerDoesNotOwn() throws Exception {
        Long otherOwnerId = data.member("OWNER");
        Long publicStoreId = jdbc.queryForObject("""
                INSERT INTO store (owner_id, name, category, address, waiting_available,
                                   reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
                VALUES (NULL, '공공 매장', 'KOREAN', 'Seoul', false, 60, 30, 10)
                RETURNING id""", Long.class);

        sales(otherOwnerId, storeId, "2030-01-19", "2030-01-19")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        sales(ownerId, 999_999_999L, "2030-01-19", "2030-01-19")
                .andExpect(status().isNotFound());
        sales(ownerId, publicStoreId, "2030-01-19", "2030-01-19")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("회원 식별이 없으면 401이다")
    void requiresMemberHeader() throws Exception {
        mockMvc.perform(get("/api/owner/stores/{storeId}/sales", storeId)
                        .queryParam("from", "2030-01-19").queryParam("to", "2030-01-19"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("from이 to보다 늦거나 기간이 없거나 날짜 형식이 틀리면 400이다")
    void rejectsInvalidPeriod() throws Exception {
        sales(ownerId, storeId, "2030-01-20", "2030-01-19").andExpect(status().isBadRequest());
        sales(ownerId, storeId, "2030/01/19", "2030-01-19").andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/owner/stores/{storeId}/sales", storeId)
                        .header("X-Member-Id", ownerId).queryParam("from", "2030-01-19"))
                .andExpect(status().isBadRequest());
    }
}
