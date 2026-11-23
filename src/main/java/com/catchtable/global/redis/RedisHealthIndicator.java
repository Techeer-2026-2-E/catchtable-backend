package com.catchtable.global.redis;

import com.catchtable.waiting.repository.ReceiptWorkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 켠 Redis 기능의 연결과 웨이팅 워커 설정을 알린다. Redis가 끊겨도 앱은 PG 경로로 계속 동작하므로
 * DOWN(503) 대신 DEGRADED(200)를 쓴다. 상태 순서는 application.yml에 있다.
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {
    static final Status DEGRADED = new Status("DEGRADED");

    private final RedisEndpoint availability;
    private final RedisEndpoint waiting;
    private final ReceiptWorkRepository ledger;
    private final boolean cacheEnabled;
    private final boolean dispatchEnabled;

    public RedisHealthIndicator(@Qualifier("availabilityRedis") RedisEndpoint availability,
                                @Qualifier("waitingRedis") RedisEndpoint waiting, ReceiptWorkRepository ledger,
                                @Value("${availability.cache.enabled:false}") boolean cacheEnabled,
                                @Value("${waiting.dispatch.enabled:false}") boolean dispatchEnabled) {
        this.availability = availability;
        this.waiting = waiting;
        this.ledger = ledger;
        this.cacheEnabled = cacheEnabled;
        this.dispatchEnabled = dispatchEnabled;
    }

    @Override
    public Health health() {
        var health = Health.up();
        boolean degraded = false;
        if (cacheEnabled) degraded |= !reachable(availability, "availabilityCache", health);
        if (dispatchEnabled) {
            degraded |= !reachable(waiting, "waitingQueue", health);
        } else if (!ledger.lanes().isEmpty()) {
            // A store was switched to the ledger, but no worker in this app will number its receipts.
            health.withDetail("waitingDispatch", "disabled with unfinished ledger work");
            degraded = true;
        }
        return (degraded ? health.status(DEGRADED) : health).build();
    }

    private static boolean reachable(RedisEndpoint redis, String name, Health.Builder health) {
        try {
            redis.use(s -> s.commands().ping());
            health.withDetail(name, "UP");
            return true;
        } catch (RuntimeException unavailable) {
            health.withDetail(name, "UNAVAILABLE");
            return false;
        }
    }
}
