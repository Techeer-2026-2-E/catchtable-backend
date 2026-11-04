package com.catchtable.notification.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.repository.AlimtalkOutboxRepository;
import com.catchtable.payment.ReconcileTestClock;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"alimtalk.enabled=true", "alimtalk.mode=solapi",
        "alimtalk.initial-delay-ms=3600000", "waiting.expiry-interval-ms=3600000",
        "alimtalk.solapi.api-key=local-key", "alimtalk.solapi.api-secret=local-secret",
        "alimtalk.solapi.channel-id=local-channel", "alimtalk.solapi.template-id=local-template",
        "alimtalk.solapi.webhook-secret=local-webhook"})
@AutoConfigureMockMvc
@Import(ReconcileTestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlimtalkHttpFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer PROVIDER = provider();
    @Autowired MockMvc mvc;
    @Autowired AlimtalkDeliveryService dispatcher;
    @Autowired AlimtalkOutboxRepository outbox;
    @Autowired MemberRepository members;
    @Autowired StoreRepository stores;
    @Autowired WaitingRepository waitings;
    @Autowired WaitingEndHistoryRepository histories;
    @Autowired ReconcileTestClock clock;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("alimtalk.solapi.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
    }

    @AfterAll
    static void stop() { PROVIDER.stop(0); }

    @Test
    void publicCallAndRecallFlowThroughRealRestClientAndAuthenticatedWebhook() throws Exception {
        clock.set(OffsetDateTime.parse("2030-01-20T12:00:00+09:00"));
        Member owner = members.save(Member.builder().name("점주").phone("010-0000-0001").userType(UserType.OWNER).build());
        Member customer = members.save(Member.builder().name("고객").phone("010-0000-0002").userType(UserType.CUSTOMER).build());
        Store store = stores.save(Store.builder().owner(owner).name("HTTP 검증 매장").address("서울")
                .category(StoreCategory.values()[0]).reservationDurationMinutes(90)
                .reservationSlotMinutes(30).arrivalGraceMinutes(10).build());
        Waiting waiting = waitings.save(Waiting.create(customer, store, LocalDate.now(clock), 2, 1));
        try {
            mvc.perform(post("/api/owner/waitings/{id}/call", waiting.getId())
                    .header("X-Member-Id", owner.getId())).andExpect(status().isOk());
            var expiry = waitings.findById(waiting.getId()).orElseThrow().getCallExpiresAt();
            dispatcher.poll();
            clock.set(OffsetDateTime.now(clock).plusSeconds(10));
            mvc.perform(post("/api/owner/waitings/{id}/recall", waiting.getId())
                    .header("X-Member-Id", owner.getId())).andExpect(status().isOk());
            dispatcher.poll();
            assertThat(REQUESTS).hasSize(2);
            assertThat(waitings.findById(waiting.getId()).orElseThrow().getCallExpiresAt()).isEqualTo(expiry);
            List<Map<String, Object>> reports = new ArrayList<>();
            for (int i = 0; i < REQUESTS.size(); i++) {
                JsonNode request = REQUESTS.get(i);
                assertThat(request.path("kakaoOptions").path("disableSms").asBoolean()).isTrue();
                UUID id = UUID.fromString(request.path("customFields").path("outboxId").asText());
                assertThat(outbox.find(id).orElseThrow().status()).isEqualTo("ACCEPTED");
                reports.add(Map.of("messageId", "http-" + (i + 1), "type", "ATA", "to", "01000000002",
                        "statusCode", "4000", "customFields", JSON.convertValue(request.path("customFields"), Map.class)));
            }
            String secret = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest("local-webhook".getBytes(StandardCharsets.UTF_8)));
            mvc.perform(post("/api/integrations/alimtalk/solapi").header("X-Solapi-Secret", secret)
                    .header("X-Solapi-Event-Name", "SINGLE-REPORT").contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsBytes(reports))).andExpect(status().isOk());
            assertThat(jdbc.queryForList("SELECT status FROM alimtalk_outbox", String.class)).containsOnly("SENT");
        } finally {
            histories.deleteAllInBatch();
            waitings.deleteAllInBatch();
            stores.delete(store);
            members.deleteAll(List.of(customer, owner));
        }
    }

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/messages/v4/send-many/detail", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody()).path("messages").get(0);
                REQUESTS.add(request);
                var item = Map.of("messageId", "http-" + REQUESTS.size(), "statusCode", "2000",
                        "customFields", JSON.convertValue(request.path("customFields"), Map.class));
                byte[] response = JSON.writeValueAsBytes(Map.of("messageList", List.of(item), "failedMessageList", List.of()));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var stream = exchange.getResponseBody()) { stream.write(response); }
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
