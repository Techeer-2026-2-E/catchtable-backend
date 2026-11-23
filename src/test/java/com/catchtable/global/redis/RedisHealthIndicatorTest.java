package com.catchtable.global.redis;

import com.catchtable.waiting.dto.WaitingLane;
import com.catchtable.waiting.repository.ReceiptWorkRepository;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedisHealthIndicatorTest {
    private final RedisEndpoint cache = mock(RedisEndpoint.class);
    private final RedisEndpoint queue = mock(RedisEndpoint.class);
    private final ReceiptWorkRepository ledger = mock(ReceiptWorkRepository.class);

    @Test
    void disabledFeaturesNeverConnect() {
        var health = new RedisHealthIndicator(cache, queue, ledger, false, false).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(cache, queue);
    }

    @Test
    void unreachableEnabledRedisIsDegradedNotDown() {
        when(cache.use(any())).thenReturn("PONG");
        when(queue.use(any())).thenThrow(new RedisException("Redis connection unavailable"));
        var health = new RedisHealthIndicator(cache, queue, ledger, true, true).health();
        assertThat(health.getStatus()).isEqualTo(RedisHealthIndicator.DEGRADED);
        assertThat(health.getDetails()).containsEntry("availabilityCache", "UP").containsEntry("waitingQueue", "UNAVAILABLE");
    }

    @Test
    void ledgerLookupFailureIsReportedAsDownInsteadOfBreakingTheEndpoint() {
        when(ledger.lanes()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        var health = new RedisHealthIndicator(cache, queue, ledger, false, false).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void ledgerWorkWithoutAWorkerIsDegraded() {
        when(ledger.lanes()).thenReturn(List.of(new WaitingLane(1L, LocalDate.of(2031, 2, 1))));
        var health = new RedisHealthIndicator(cache, queue, ledger, false, false).health();
        assertThat(health.getStatus()).isEqualTo(RedisHealthIndicator.DEGRADED);
        assertThat(health.getDetails()).containsKey("waitingDispatch");
    }
}
