package com.catchtable.notification.durability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(WaitingSseProperties.class)
public class WaitingSseConfiguration {
    @Bean
    public ThreadPoolTaskScheduler waitingSseScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("waiting-sse-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
