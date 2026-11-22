package com.catchtable.global.redis;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

@Configuration
public class RedisProductConfiguration {
    @Bean(destroyMethod="shutdown")
    ClientResources productRedisResources() {
        return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
    }

    @Bean(destroyMethod="close")
    RedisEndpoint availabilityRedis(ClientResources resources,
            @Value("${availability.cache.uri:redis://localhost:6379}") String uri,
            @Value("${availability.cache.timeout:150ms}") Duration timeout) {
        return new RedisEndpoint(resources,uri,timeout,"");
    }

    @Bean(destroyMethod="close")
    RedisEndpoint waitingRedis(ClientResources resources,
            @Value("${waiting.dispatch.uri:redis-sentinel://localhost:26379/0?sentinelMasterId=ct-waiting}") String uri,
            @Value("${waiting.dispatch.timeout:300ms}") Duration timeout,
            @Value("${waiting.dispatch.sentinel-password:}") String sentinelPassword) {
        return new RedisEndpoint(resources,uri,timeout,sentinelPassword);
    }

    @Bean
    ThreadPoolTaskScheduler waitingReceiptScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("waiting-receipt-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
