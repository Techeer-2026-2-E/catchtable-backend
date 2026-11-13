package com.catchtable.payment.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class TossPaymentsConfig {

    @Bean
    public TossPaymentsClient tossPaymentsClient(RestClient.Builder builder,
                                                 PaymentCallGate gate, MeterRegistry metrics,
                                                 @Value("${toss.base-url}") String baseUrl,
                                                 @Value("${toss.secret-key}") String secretKey,
                                                 @Value("${toss.connect-timeout}") Duration connectTimeout,
                                                 @Value("${toss.read-timeout}") Duration readTimeout,
                                                 @Value("${toss.call-timeout:35s}") Duration callTimeout) {
        if (connectTimeout.toMillis() < 1 || readTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("Toss connect/read timeout must be at least 1ms");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new BoundedTossPaymentsClient(
                new RestTossPaymentsClient(builder.requestFactory(requestFactory), baseUrl, secretKey), gate, metrics, callTimeout);
    }
}
