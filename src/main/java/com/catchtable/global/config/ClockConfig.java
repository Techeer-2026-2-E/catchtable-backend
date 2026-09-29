package com.catchtable.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

// 매장 현지 시각(Asia/Seoul) 기준 현재 시각. 테스트에서는 고정 Clock으로 교체한다
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
