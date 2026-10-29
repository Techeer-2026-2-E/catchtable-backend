package com.catchtable.payment.controller;

import com.catchtable.payment.ConfirmedEventCounter;
import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.catchtable.payment.PaymentTestData.AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 트랜잭션 없음, 고정 Clock, UUID 데이터는 매 테스트 후 정리
@SpringBootTest
@AutoConfigureMockMvc
@Import({FakeTossPaymentsClient.Config.class, ConfirmedEventCounter.Config.class})
class PaymentReconcileControllerTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-20T12:00:00+09:00");

    @Autowired MockMvc mockMvc;
    @Autowired FakeTossPaymentsClient toss;
    @Autowired JdbcTemplate jdbc;
    @MockBean Clock clock;

    private final List<Long> members = new ArrayList<>();
    private PaymentTestData data;
    private Long customerId;
    private Long ownerId;
    private Long storeId;
    private Long paymentId;
    private String orderId;

    @BeforeEach
    void setUp() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(NOW.toInstant());
        toss.reset();
        data = new PaymentTestData(jdbc);
        customerId = member("CUSTOMER");
        ownerId = member("OWNER");
        storeId = data.store(ownerId);
        Long reservationId = data.hold(customerId, storeId, 0);
        paymentId = data.payment(customerId, reservationId, "TIMEOUT", 0);
        orderId = data.orderId(paymentId);
        data.protect(reservationId, paymentId, "pk-1", NOW.minusMinutes(1));
        toss.query(orderId, "pk-1", "DONE", AMOUNT);
    }

    @AfterEach
    void cleanUp() {
        data.deleteStore(storeId, members);
    }

    @Test
    @DisplayName("재확인하면 Toss 결과를 반영한 결제 상세를 200으로 반환한다")
    void syncReturnsReconciledPayment() throws Exception {
        mockMvc.perform(post("/api/user/payments/{paymentId}/sync", paymentId).header("X-Member-Id", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.refundRequired").value(false))
                .andExpect(jsonPath("$.refunds").isArray());
        assertThat(reservationStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("남의 결제는 404, 점주는 403, 헤더가 없으면 401이고 Toss를 부르지 않는다")
    void syncChecksMember() throws Exception {
        Long other = member("CUSTOMER");

        mockMvc.perform(post("/api/user/payments/{paymentId}/sync", paymentId).header("X-Member-Id", other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mockMvc.perform(post("/api/user/payments/{paymentId}/sync", paymentId).header("X-Member-Id", ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
        mockMvc.perform(post("/api/user/payments/{paymentId}/sync", paymentId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(toss.queries()).isZero();
        assertThat(paymentStatus()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("웹훅은 본문 상태가 아닌 Toss 재조회 결과를 반영하고, 반복해도 같은 결과다")
    void webhookReconcilesByQuery() throws Exception {
        // 본문 상태(CANCELED)는 무시하고 조회 결과(DONE) 반영
        String body = """
                {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2030-01-20T12:00:00.000000",
                 "data":{"paymentKey":"pk-1","orderId":"%s","status":"CANCELED"}}
                """.formatted(orderId);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/user/webhooks/payments").contentType("application/json").content(body))
                    .andExpect(status().isOk());
        }

        assertThat(paymentStatus()).isEqualTo("DONE");
        assertThat(reservationStatus()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id = ?",
                Long.class, paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("가상계좌 입금 콜백 형식(최상위 orderId)도 결제를 찾아 재조회한다")
    void depositCallbackUsesTopLevelOrderId() throws Exception {
        String body = """
                {"createdAt":"2030-01-20T12:00:00.000000","secret":"x","status":"DONE",
                 "transactionKey":"tx","orderId":"%s"}
                """.formatted(orderId);

        mockMvc.perform(post("/api/user/webhooks/payments").contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("DONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"orderId\":\"no-such-order\"}}",
            "{\"eventType\":\"PAYMENT_STATUS_CHANGED\"}",
            "not json",
            ""
    })
    @DisplayName("모르는 결제·잘못된 본문도 200이고 아무것도 바꾸지 않는다")
    void unknownOrMalformedWebhookReturnsOk(String body) throws Exception {
        mockMvc.perform(post("/api/user/webhooks/payments").contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("TIMEOUT");
        assertThat(toss.queries()).isZero();
    }

    private Long member(String type) {
        Long memberId = data.member(type);
        members.add(memberId);
        return memberId;
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String reservationStatus() {
        return jdbc.queryForObject("""
                SELECT r.status FROM reservation r JOIN payment p ON p.reservation_id = r.id WHERE p.id = ?
                """, String.class, paymentId);
    }
}
