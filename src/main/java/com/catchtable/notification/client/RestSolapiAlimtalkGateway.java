package com.catchtable.notification.client;

import com.catchtable.notification.config.SolapiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

public class RestSolapiAlimtalkGateway implements AlimtalkGateway {
    private final RestClient client;
    private final SolapiProperties properties;
    private final Clock clock;

    public RestSolapiAlimtalkGateway(RestClient.Builder builder, SolapiProperties properties, Clock clock) {
        if (List.of(properties.getApiKey(), properties.getApiSecret(), properties.getChannelId(),
                properties.getTemplateId()).stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("SOLAPI api-key/api-secret/channel-id/template-id are required");
        }
        this.properties = properties;
        this.clock = clock;
        this.client = builder.baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public Result send(Request request) {
        Map<String, Object> message = Map.of("to", request.recipient(), "type", "ATA", "country", "82",
                "customFields", Map.of("outboxId", request.id().toString(), "attempt", "" + request.attempt()),
                "kakaoOptions", Map.of("pfId", properties.getChannelId(), "templateId", properties.getTemplateId(),
                        "disableSms", true, "variables", Map.of("#{매장명}", request.storeName(),
                                "#{대기번호}", "" + request.waitNumber(), "#{입장기한}", request.expiresAt()
                                        .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
                                        .format(DateTimeFormatter.ofPattern("HH:mm")))));
        try {
            JsonNode body = client.post().uri("/messages/v4/send-many/detail")
                    .header("Authorization", authorization()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messages", List.of(message), "showMessageList", true,
                            "allowDuplicates", false, "strict", true)).retrieve().body(JsonNode.class);
            if (body == null) return Result.unknown(null);
            JsonNode messages = body.path("messageList");
            JsonNode failures = body.path("failedMessageList");
            if ((!messages.isMissingNode() && !messages.isNull() && !messages.isArray())
                    || (!failures.isMissingNode() && !failures.isNull() && !failures.isArray())) {
                return Result.unknown(null);
            }
            if (messages.isArray() && messages.size() == 1 && (!failures.isArray() || failures.isEmpty())) {
                return response(messages.get(0), request, null, false);
            }
            if (failures.isArray() && failures.size() == 1 && (!messages.isArray() || messages.isEmpty())) {
                return response(failures.get(0), request, null, true);
            }
            return Result.unknown(null);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 429) return new Result(Outcome.RETRYABLE_FAILURE, null, "HTTP_429");
            if (Set.of(400, 401, 403, 404, 422).contains(code)) {
                return new Result(Outcome.PERMANENT_FAILURE, null, "HTTP_" + code);
            }
            return Result.unknown(null);
        } catch (RuntimeException e) {
            return Result.unknown(null);
        }
    }

    @Override
    public Result lookup(Request request, String messageId) {
        try {
            JsonNode body = client.get().uri(builder -> builder.path("/messages/v4/list")
                            .queryParam("criteria", "messageId").queryParam("cond", "eq")
                            .queryParam("value", messageId).build())
                    .header("Authorization", authorization()).retrieve().body(JsonNode.class);
            if (body == null) return Result.unknown(messageId);
            return response(body.path("messageList").path(messageId), request, messageId, false);
        } catch (RuntimeException e) {
            return Result.unknown(messageId);
        }
    }

    private Result response(JsonNode message, Request request, String expectedId, boolean registrationRejected) {
        String id = message.path("messageId").asText("");
        JsonNode custom = message.path("customFields");
        if (!id.matches("[A-Za-z0-9_-]{1,64}") || (expectedId != null && !expectedId.equals(id))
                || !request.id().toString().equals(custom.path("outboxId").asText())
                || !Integer.toString(request.attempt()).equals(custom.path("attempt").asText())
                || (message.has("to") && !request.recipient().equals(message.path("to").asText()))
                || (message.has("type") && !"ATA".equals(message.path("type").asText()))) {
            return Result.unknown(expectedId);
        }
        Result result = SolapiStatus.result(message.path("statusCode").asText(""), id);
        // 등록 실패 목록은 미발송의 근거다. 성공 상태와 충돌하는 응답은 보류한다.
        if (registrationRejected && (result.outcome() == Outcome.ACCEPTED || result.outcome() == Outcome.DELIVERED)) {
            return Result.unknown(id);
        }
        return result;
    }

    private String authorization() {
        String date = clock.instant().toString();
        String salt = UUID.randomUUID().toString().replace("-", "");
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(properties.getApiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(hmac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
            return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s"
                    .formatted(properties.getApiKey(), date, salt, signature);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign SOLAPI request", e);
        }
    }

}
