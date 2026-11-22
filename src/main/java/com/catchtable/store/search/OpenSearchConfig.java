package com.catchtable.store.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

// 키워드 검색 엔진이 opensearch이거나 재색인을 켤 때만 OpenSearch 연결 생성
@Configuration
@ConditionalOnExpression("'${catchtable.search.engine:postgres}' == 'opensearch' or '${catchtable.search.reindex:false}' == 'true'")
public class OpenSearchConfig {

    @Bean
    public StoreSearchIndex storeSearchIndex(RestClient.Builder builder, ObjectMapper mapper,
                                             @Value("${catchtable.search.engine:postgres}") String engine,
                                             @Value("${catchtable.search.opensearch.url:http://localhost:9200}") String url,
                                             @Value("${catchtable.search.opensearch.alias:stores}") String alias,
                                             @Value("${catchtable.search.opensearch.connect-timeout:1s}") Duration connectTimeout,
                                             @Value("${catchtable.search.opensearch.read-timeout:2s}") Duration readTimeout,
                                             @Value("${catchtable.search.opensearch.entered-store-boost:1.1}") double enteredStoreBoost) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        RestClient client = builder.baseUrl(url).requestFactory(requestFactory).build();
        return new StoreSearchIndex(client, mapper, alias, enteredStoreBoost, "opensearch".equals(engine));
    }

    @Bean
    public StoreReindexService storeReindexService(StoreSearchIndex index,
                                                   JdbcTemplate jdbcTemplate,
                                                   ObjectMapper mapper, Clock clock) {
        return new StoreReindexService(index, jdbcTemplate, mapper, clock);
    }
}
