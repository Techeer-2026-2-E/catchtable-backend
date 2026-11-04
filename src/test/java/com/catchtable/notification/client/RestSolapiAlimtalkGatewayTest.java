package com.catchtable.notification.client;

import com.catchtable.notification.config.SolapiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RestSolapiAlimtalkGatewayTest {
    private static final String URL = "https://solapi.invalid";
    private static final String SEND = URL + "/messages/v4/send-many/detail";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AlimtalkGateway.Request request = new AlimtalkGateway.Request(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), 1, "010-0000-0002", "테스트 매장", 7,
            OffsetDateTime.parse("2030-01-20T03:10:00Z"));
    private MockRestServiceServer server;
    private RestSolapiAlimtalkGateway gateway;

    @BeforeEach
    void prepare() {
        var properties = new SolapiProperties();
        properties.setBaseUrl(URL);
        properties.setApiKey("dummy-key");
        properties.setApiSecret("dummy-secret");
        properties.setChannelId("dummy-channel");
        properties.setTemplateId("dummy-template");
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RestSolapiAlimtalkGateway(builder, properties,
                Clock.fixed(Instant.parse("2030-01-20T03:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void signsRequestAndSendsOnlyAlimtalkWithTrustedCorrelation() throws Exception {
        server.expect(requestTo(SEND)).andExpect(method(HttpMethod.POST))
                .andExpect(http -> {
                    String auth = http.getHeaders().getFirst("Authorization");
                    assertThat(auth).startsWith("HMAC-SHA256 apiKey=dummy-key, date=2030-01-20T03:00:00Z, salt=");
                    String salt = auth.split("salt=")[1].split(",")[0];
                    assertThat(salt).matches("[a-f0-9]{32}");
                    try {
                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec("dummy-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                        String signature = HexFormat.of().formatHex(mac.doFinal(
                                ("2030-01-20T03:00:00Z" + salt).getBytes(StandardCharsets.UTF_8)));
                        assertThat(auth).endsWith("signature=" + signature);
                    } catch (java.security.GeneralSecurityException e) {
                        throw new AssertionError(e);
                    }
                })
                .andExpect(jsonPath("$.messages[0].to").value("01000000002"))
                .andExpect(jsonPath("$.messages[0].type").value("ATA"))
                .andExpect(jsonPath("$.messages[0].kakaoOptions.disableSms").value(true))
                .andExpect(jsonPath("$.messages[0].kakaoOptions.variables['#{입장기한}']").value("12:10"))
                .andExpect(jsonPath("$.messages[0].customFields.outboxId").value(request.id().toString()))
                .andExpect(jsonPath("$.showMessageList").value(true))
                .andRespond(withSuccess(response("2000", false), MediaType.APPLICATION_JSON));
        assertThat(gateway.send(request).outcome()).isEqualTo(AlimtalkGateway.Outcome.ACCEPTED);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"2000,ACCEPTED", "3000,ACCEPTED", "4000,DELIVERED", "2024,RETRYABLE_FAILURE",
            "3012,RETRYABLE_FAILURE", "3024,RETRYABLE_FAILURE", "3053,RETRYABLE_FAILURE",
            "3105,PERMANENT_FAILURE", "3014,UNKNOWN", "3048,UNKNOWN", "7777,UNKNOWN"})
    void decodesProviderStatuses(String code, AlimtalkGateway.Outcome expected) throws Exception {
        server.expect(requestTo(SEND)).andRespond(withSuccess(response(code, false), MediaType.APPLICATION_JSON));
        assertThat(gateway.send(request).outcome()).isEqualTo(expected);
        server.verify();
    }

    @Test
    void registrationFailureIsNotReportedAsAcceptance() throws Exception {
        server.expect(requestTo(SEND)).andRespond(withSuccess(response("2024", true), MediaType.APPLICATION_JSON));
        assertThat(gateway.send(request).outcome()).isEqualTo(AlimtalkGateway.Outcome.RETRYABLE_FAILURE);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"429,RETRYABLE_FAILURE", "400,PERMANENT_FAILURE", "403,PERMANENT_FAILURE",
            "408,UNKNOWN", "409,UNKNOWN", "500,UNKNOWN", "502,UNKNOWN", "504,UNKNOWN"})
    void httpResponseAloneOnlyConfirmsKnownRejection(int status, AlimtalkGateway.Outcome expected) {
        server.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThat(gateway.send(request).outcome()).isEqualTo(expected);
        server.verify();
    }

    @Test
    void responseLossIsUnknown() {
        server.expect(requestTo(SEND)).andRespond(withException(new SocketTimeoutException("response lost")));
        assertThat(gateway.send(request).outcome()).isEqualTo(AlimtalkGateway.Outcome.UNKNOWN);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"messageList\":{\"unexpected\":{}}}", "not-json"})
    void invalidResponseNeverConfirmsDelivery(String body) {
        server.expect(requestTo(SEND)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(gateway.send(request).outcome()).isEqualTo(AlimtalkGateway.Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    void mismatchedSendCorrelationIsUnknown() throws Exception {
        String body = response("2000", false).replace(request.id().toString(), UUID.randomUUID().toString());
        server.expect(requestTo(SEND)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(gateway.send(request).outcome()).isEqualTo(AlimtalkGateway.Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    void looksUpOnlyTheKnownMessageAndChecksTheOriginalRecipient() throws Exception {
        server.expect(requestTo(URL + "/messages/v4/list?criteria=messageId&cond=eq&value=m1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("messageList", Map.of("m1", message("4000")))),
                        MediaType.APPLICATION_JSON));
        assertThat(gateway.lookup(request, "m1").outcome()).isEqualTo(AlimtalkGateway.Outcome.DELIVERED);
        server.verify();
    }

    @Test
    void lookupOfAnotherRecipientPreservesUnknown() throws Exception {
        var other = message("4000");
        other.put("to", "01099999999");
        server.expect(anything()).andRespond(withSuccess(mapper.writeValueAsString(
                Map.of("messageList", Map.of("m1", other))), MediaType.APPLICATION_JSON));
        var result = gateway.lookup(request, "m1");
        assertThat(result.outcome()).isEqualTo(AlimtalkGateway.Outcome.UNKNOWN);
        assertThat(result.messageId()).isEqualTo("m1");
        server.verify();
    }

    private Map<String, Object> message(String code) {
        return new HashMap<>(Map.of("messageId", "m1", "statusCode", code, "to", "01000000002", "type", "ATA",
                "customFields", Map.of("outboxId", request.id().toString(), "attempt", "1")));
    }

    private String response(String code, boolean failed) throws Exception {
        return mapper.writeValueAsString(Map.of("messageList", failed ? List.of() : List.of(message(code)),
                "failedMessageList", failed ? List.of(message(code)) : List.of()));
    }
}
