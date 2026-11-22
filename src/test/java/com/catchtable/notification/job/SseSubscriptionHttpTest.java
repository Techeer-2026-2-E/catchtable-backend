package com.catchtable.notification.job;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.payment.PaymentTestData;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class SseSubscriptionHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationEventPublisher events;
    @Autowired SseEmitterRepository connections;
    @Autowired MeterRegistry metrics;

    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(15)
    void realServletSendsGreetingThenCommittedEvent(boolean ownerStream) throws Exception {
        var data = new PaymentTestData(jdbc);
        long owner = data.member("OWNER"), customer = data.member("CUSTOMER"), store = data.store(owner);
        long recipient = ownerStream ? owner : customer;
        String path = ownerStream ? "/api/owner/stores/" + store + "/waitings/subscribe" : "/api/user/notifications/subscribe";
        var type = ownerStream ? NotificationType.WAITING_CHANGED : NotificationType.RESERVATION_CANCELED;
        var opened = metrics.counter("notification.sse.connections.ready");
        double before = opened.count();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "?memberId=" + recipient))
                .timeout(Duration.ofSeconds(5)).header("Accept", "text/event-stream").build();
        try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value ->
                        assertThat(value).startsWith("text/event-stream"));
                assertThat(frame(reader)).contains("event:connect", "data:connected");
                assertThat(opened.count()).isEqualTo(before + 1);
                assertThat(connections.findAllByMemberId(recipient)).hasSize(1);
                tx.executeWithoutResult(ignored -> events.publishEvent(new CustomerNotificationEvent(recipient, type, store, "알림")));
                assertThat(frame(reader)).contains("event:" + type.name(), "id:", "data:", "\"targetId\":" + store);
            }
        } finally {
            for (var emitter : connections.findAllByMemberId(recipient)) {
                connections.delete(recipient, emitter);
                emitter.complete();
            }
            data.deleteStore(store, List.of(owner, customer));
        }
    }

    private String frame(BufferedReader input) throws Exception {
        var body = new StringBuilder();
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) body.append(line).append('\n');
        return body.toString();
    }
}
