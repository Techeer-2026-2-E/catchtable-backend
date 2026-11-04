package com.catchtable.notification.controller;

import com.catchtable.notification.client.SolapiStatus;
import com.catchtable.notification.config.SolapiProperties;
import com.catchtable.notification.repository.AlimtalkOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alimtalk.enabled", havingValue = "true")
public class SolapiWebhookController {
    private final SolapiProperties properties;
    private final AlimtalkOutboxRepository outbox;
    private final Clock clock;

    @PostMapping("/api/integrations/alimtalk/solapi")
    public void report(@RequestHeader(value = "X-Solapi-Secret", required = false) String secret,
                       @RequestHeader(value = "X-Solapi-Event-Name", required = false) String event,
                       @RequestBody JsonNode reports) {
        authenticate(secret);
        if (!"SINGLE-REPORT".equals(event)) return;
        if (!reports.isArray()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report array");
        for (JsonNode report : reports) receive(report);
    }

    private void receive(JsonNode report) {
        JsonNode custom = report.path("customFields");
        UUID id;
        int attempt;
        try {
            id = UUID.fromString(custom.path("outboxId").asText());
            attempt = Integer.parseInt(custom.path("attempt").asText());
        } catch (IllegalArgumentException ignored) {
            return; // 같은 공급사 계정의 다른 서비스 메시지는 처리하지 않는다.
        }
        var delivery = outbox.find(id).orElse(null);
        String messageId = report.path("messageId").asText("");
        if (delivery == null || !delivery.transportMode().equals("solapi")
                || !messageId.matches("[A-Za-z0-9_-]{1,64}") || !"ATA".equals(report.path("type").asText())
                || !delivery.recipient().replaceAll("[\\s-]", "").equals(report.path("to").asText())) return;
        outbox.recordResult(id, attempt, SolapiStatus.result(report.path("statusCode").asText(), messageId),
                OffsetDateTime.now(clock));
    }

    private void authenticate(String received) {
        if (properties.getWebhookSecret().isBlank() || received == null || received.length() != 40) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
        }
        try {
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    received.getBytes(StandardCharsets.US_ASCII))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
