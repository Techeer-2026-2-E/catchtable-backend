package com.catchtable.payment.client;

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
                                                 @Value("${toss.base-url}") String baseUrl,
                                                 @Value("${toss.secret-key}") String secretKey,
                                                 @Value("${toss.connect-timeout}") Duration connectTimeout,
                                                 @Value("${toss.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new RestTossPaymentsClient(builder.requestFactory(requestFactory), baseUrl, secretKey);
    }
}
