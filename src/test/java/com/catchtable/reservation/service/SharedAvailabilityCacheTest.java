package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.redis.RedisEndpoint;
import com.catchtable.reservation.dto.AvailabilityResponse;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked","rawtypes"})
class SharedAvailabilityCacheTest {
    private final RedisEndpoint redis=mock(RedisEndpoint.class);
    private final ReservationAvailabilityService source=mock(ReservationAvailabilityService.class);
    private final RedisCommands<String,String> commands=mock(RedisCommands.class);
    private final SimpleMeterRegistry meters=new SimpleMeterRegistry();
    private final Clock clock=Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AvailabilityCache cache=new AvailabilityCache(redis,source,JsonMapper.builder().addModule(new JavaTimeModule()).build(),
            db,meters,clock,true,"test:",1);
    private final LocalDate day=LocalDate.of(2031,1,2);

    @Test void jsonHitPreservesTheSeoulOffset() {
        when(db.queryForObject("SELECT enable_availability_tracking()",Boolean.class)).thenReturn(true);
        cache.startTracking();
        var session=new RedisEndpoint.Session(UUID.randomUUID(),commands);
        doAnswer(call->((Function)call.getArgument(0)).apply(session)).when(redis).use(any());
        when(commands.eval(anyString(),eq(ScriptOutputType.MULTI),any(String[].class),any(String[].class))).thenReturn(List.of("pinned","""
                {"storeId":1,"date":"2031-01-02","partySize":2,"slots":[{"reservationStartAt":"2031-01-02T12:00:00+09:00","availableTableCount":3,"available":true}]}
                """));
        assertThat(cache.get(1,day,2).slots().getFirst().reservationStartAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        verifyNoInteractions(source);
    }

    @Test void onlyLeaderUsesBypassPermitAndNothingIsRetainedLocally() throws Exception {
        when(db.queryForObject("SELECT enable_availability_tracking()",Boolean.class)).thenReturn(true);
        cache.startTracking();
        when(redis.use(any())).thenThrow(new RedisException("disconnected"));
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var value=new AvailabilityResponse(1L,day,2,List.of());
        when(source.getAvailability(1L,day,2)).thenAnswer(c->{entered.countDown();finish.await(5,TimeUnit.SECONDS);return value;});
        try(var callers=Executors.newFixedThreadPool(2)) {
            var a=callers.submit(()->cache.get(1,day,2));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var b=callers.submit(()->cache.get(1,day,2));
            try {
                long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(meters.find("availability.cache.join").counter()==null && System.nanoTime()<limit) Thread.sleep(1);
                assertThat(meters.get("availability.cache.join").counter().count()).isEqualTo(1);
                assertThatThrownBy(()->cache.get(2,day,2)).isInstanceOf(BusinessException.class);
                verify(source,never()).getAvailability(2L,day,2);
            } finally { finish.countDown(); }
            assertThat(a.get()).isEqualTo(value);assertThat(b.get()).isEqualTo(value);
        }
        cache.get(1,day,2);
        verify(source,times(2)).getAvailability(1L,day,2);
    }
}
