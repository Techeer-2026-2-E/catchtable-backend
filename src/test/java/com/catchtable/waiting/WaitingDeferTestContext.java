package com.catchtable.waiting;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

// 순번 연기 테스트 공용 컨텍스트 (컨텍스트 1개·작은 풀, DB 연결 수 초과 방지)
// 실제 스케줄 비활성화, 미래 고정 시각
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "waiting.expiry-interval-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@Import(WaitingDeferTestContext.FixedTime.class)
public @interface WaitingDeferTestContext {

    @TestConfiguration
    class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2030-01-10T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
