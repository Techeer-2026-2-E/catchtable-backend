package com.catchtable.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

public class RestTossPaymentsClient implements TossPaymentsClient {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";

    private final RestClient restClient;

    // 타임아웃은 builder의 requestFactory로 지정
    public RestTossPaymentsClient(RestClient.Builder builder, String baseUrl, String secretKey) {
        String credential = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credential)
                .build();
    }

    @Override
    public TossPaymentResponse confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        return post("/v1/payments/confirm", new ConfirmRequest(paymentKey, orderId, amount), idempotencyKey);
    }

    @Override
    public TossPaymentResponse getByPaymentKey(String paymentKey) {
        return execute(() -> restClient.get().uri("/v1/payments/{paymentKey}", paymentKey)
                .retrieve().body(TossPaymentResponse.class));
    }

    @Override
    public TossPaymentResponse getByOrderId(String orderId) {
        return execute(() -> restClient.get().uri("/v1/payments/orders/{orderId}", orderId)
                .retrieve().body(TossPaymentResponse.class));
    }

    @Override
    public TossPaymentResponse cancel(String paymentKey, Long cancelAmount, String cancelReason,
                                      String idempotencyKey) {
        return post("/v1/payments/{paymentKey}/cancel", new CancelRequest(cancelReason, cancelAmount),
                idempotencyKey, paymentKey);
    }

    private TossPaymentResponse post(String path, Object body, String idempotencyKey, Object... uriVariables) {
        return execute(() -> restClient.post().uri(path, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (idempotencyKey != null) {
                        headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                    }
                })
                .body(body)
                .retrieve().body(TossPaymentResponse.class));
    }

    private TossPaymentResponse execute(Supplier<TossPaymentResponse> request) {
        try {
            return request.get();
        } catch (ResourceAccessException exception) {
            throw new TossPaymentsUnknownResultException("Toss 응답을 받지 못했습니다.", exception);
        } catch (RestClientResponseException exception) {
            ErrorBody error = errorBody(exception);
            if (exception.getStatusCode().is5xxServerError() || IDEMPOTENT_REQUEST_PROCESSING.equals(error.code())) {
                throw new TossPaymentsUnknownResultException("Toss 처리 결과를 알 수 없습니다: " + error.code(), exception);
            }
            throw new TossPaymentsException(exception.getStatusCode().value(), error.code(), error.message());
        }
    }

    private static ErrorBody errorBody(RestClientResponseException exception) {
        try {
            ErrorBody body = exception.getResponseBodyAs(ErrorBody.class);
            if (body != null) {
                return body;
            }
        } catch (RuntimeException ignored) {
            // 본문 없음·형식 다름
        }
        return new ErrorBody(null, exception.getStatusText());
    }

    private record ConfirmRequest(String paymentKey, String orderId, long amount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CancelRequest(String cancelReason, Long cancelAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(String code, String message) {
    }
}
