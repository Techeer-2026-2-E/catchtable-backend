package com.catchtable.notification.config;

import com.catchtable.notification.client.AlimtalkGateway;
import com.catchtable.notification.client.RestSolapiAlimtalkGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SolapiProperties.class)
@ConditionalOnProperty(name = "alimtalk.enabled", havingValue = "true")
public class AlimtalkConfiguration {
    @Bean
    @ConditionalOnProperty(name = "alimtalk.mode", havingValue = "simulation", matchIfMissing = true)
    public AlimtalkGateway simulatedAlimtalkGateway() {
        return new AlimtalkGateway() {
            @Override
            public Result send(Request request) {
                return new Result(Outcome.DELIVERED, "simulation-" + request.id() + "-" + request.attempt(), null);
            }

            @Override
            public Result lookup(Request request, String messageId) {
                return messageId.startsWith("simulation-")
                        ? new Result(Outcome.DELIVERED, messageId, null) : Result.unknown(messageId);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "alimtalk.mode", havingValue = "solapi")
    public AlimtalkGateway solapiAlimtalkGateway(SolapiProperties properties, Clock clock) {
        if (properties.getWebhookSecret().isBlank()) {
            throw new IllegalArgumentException("SOLAPI webhook-secret is required in solapi mode");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestSolapiAlimtalkGateway(RestClient.builder().requestFactory(factory), properties, clock);
    }
}
