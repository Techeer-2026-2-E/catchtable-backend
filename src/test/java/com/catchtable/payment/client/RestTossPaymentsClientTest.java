package com.catchtable.payment.client;

import com.catchtable.payment.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 Toss 호출 없음
class RestTossPaymentsClientTest {

    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("test_sk_dummy:".getBytes(StandardCharsets.UTF_8));
    private static final String PAYMENT_JSON = """
            {
              "mId": "tosspayments",
              "paymentKey": "pk-1",
              "orderId": "order-1",
              "orderName": "예약금",
              "status": "%s",
              "method": "카드",
              "totalAmount": 10000,
              "balanceAmount": %d,
              "approvedAt": "2030-01-10T12:00:00+09:00",
              "receipt": { "url": "https://receipt" },
              "failure": null,
              "cancels": null
            }
            """;

    private MockRestServiceServer server;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestTossPaymentsClient(builder, BASE_URL, "test_sk_dummy");
    }

    @Test
    void confirmSendsAuthIdempotencyKeyAndBody() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", AUTHORIZATION))
                .andExpect(header("Idempotency-Key", "key-1"))
                .andExpect(content().json("""
                        {"paymentKey":"pk-1","orderId":"order-1","amount":10000}
                        """, true))
                .andRespond(withSuccess(PAYMENT_JSON.formatted("DONE", 10000), MediaType.APPLICATION_JSON));

        TossPaymentResponse response = client.confirm("pk-1", "order-1", 10000, "key-1");

        server.verify();
        assertThat(response.paymentKey()).isEqualTo("pk-1");
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(response.method()).isEqualTo("카드");
        assertThat(response.approvedAt()).isEqualTo(OffsetDateTime.parse("2030-01-10T12:00:00+09:00"));
        assertThat(response.receiptUrl()).isEqualTo("https://receipt");
        assertThat(response.canceledAmount()).isZero();
    }

    @Test
    void queriesByPaymentKeyAndOrderId() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", AUTHORIZATION))
                .andRespond(withSuccess(PAYMENT_JSON.formatted("DONE", 10000), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/order-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PAYMENT_JSON.formatted("PARTIAL_CANCELED", 7000), MediaType.APPLICATION_JSON));

        assertThat(client.getByPaymentKey("pk-1").paymentStatus()).isEqualTo(PaymentStatus.DONE);
        TossPaymentResponse byOrder = client.getByOrderId("order-1");

        server.verify();
        assertThat(byOrder.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(byOrder.canceledAmount()).isEqualTo(3000);
    }

    @Test
    void cancelSendsReasonAmountAndOmitsMissingKey() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json("""
                        {"cancelReason":"고객 취소","cancelAmount":3000}
                        """, true))
                .andRespond(withSuccess(PAYMENT_JSON.formatted("PARTIAL_CANCELED", 7000), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/payments/pk-1/cancel"))
                .andExpect(header("Idempotency-Key", "key-2"))
                .andExpect(content().json("""
                        {"cancelReason":"점주 취소"}
                        """, true))
                .andRespond(withSuccess(PAYMENT_JSON.formatted("CANCELED", 0), MediaType.APPLICATION_JSON));

        assertThat(client.cancel("pk-1", 3000L, "고객 취소", null).paymentStatus())
                .isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(client.cancel("pk-1", null, "점주 취소", "key-2").paymentStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        server.verify();
    }

    @Test
    void clientErrorIsAFailureWithTossCodeAndMessage() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"REJECT_CARD_PAYMENT","message":"한도초과 혹은 잔액부족으로 결제에 실패했습니다."}
                                """));

        assertThatThrownBy(() -> client.confirm("pk-1", "order-1", 10000, "key-1"))
                .isInstanceOfSatisfying(TossPaymentsException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(400);
                    assertThat(exception.getCode()).isEqualTo("REJECT_CARD_PAYMENT");
                    assertThat(exception.getMessage()).isEqualTo("한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
                });
    }

    @Test
    void timeoutServerErrorAndProcessingAreUnknownResults() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 시스템 처리 작업이 실패했습니다."}
                                """));
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 멱등 요청이 처리 중입니다."}
                                """));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.confirm("pk-1", "order-1", 10000, "key-1"))
                    .isInstanceOf(TossPaymentsUnknownResultException.class);
        }
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "READY, READY", "IN_PROGRESS, READY", "WAITING_FOR_DEPOSIT, READY", "DONE, DONE",
            "ABORTED, FAILED", "EXPIRED, CANCELED", "PARTIAL_CANCELED, PARTIAL_REFUNDED", "CANCELED, REFUNDED"
    })
    void convertsTossStatus(String tossStatus, PaymentStatus expected) {
        assertThat(response(tossStatus).paymentStatus()).isEqualTo(expected);
    }

    @Test
    void rejectsUnknownTossStatus() {
        assertThatThrownBy(() -> response("NEW_STATUS").paymentStatus()).isInstanceOf(IllegalStateException.class);
    }

    private static TossPaymentResponse response(String status) {
        return new TossPaymentResponse("pk-1", "order-1", "예약금", status, null, 10000, 10000, null, null, null);
    }
}
