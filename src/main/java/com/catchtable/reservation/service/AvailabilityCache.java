package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.global.redis.RedisEndpoint;
import com.catchtable.reservation.dto.AvailabilityResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.jdbc.support.SqlArrayValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Orchestrate outside JPA's transaction. Followers and over-capacity bypasses never obtain a DB connection. */
@Service
public class AvailabilityCache {
    private record Query(long store,LocalDate date,int party) {}
    private record Flight(Query query,String versionedKey) {}
    private record Lookup(String key,String json) {}
    private record Dirty(long store,String[] scopes,UUID revision) {}
    private static final String LOOKUP="""
            local function marker(key,seed)
              local value=redis.call('GET',key)
              if not value then redis.call('SET',key,seed,'EX',86400); value=seed end
              return value
            end
            local key=ARGV[1]..marker(KEYS[1],ARGV[2])..':'..marker(KEYS[2],ARGV[3])..':'..marker(KEYS[3],ARGV[4])
            return {key,redis.call('GET',key) or ''}
            """;
    private final RedisEndpoint redis;
    private final ReservationAvailabilityService source;
    private final ObjectMapper json;
    private final ObjectReader cachedJson;
    private final String instance=UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicLong nonces=new java.util.concurrent.atomic.AtomicLong();
    private final JdbcTemplate db;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final String prefix;
    private final Semaphore bypass;
    private final boolean enabled;
    private volatile boolean tracking;
    private UUID connectionGeneration;
    private final ConcurrentHashMap<Flight,CompletableFuture<AvailabilityResponse>> flights=new ConcurrentHashMap<>();

    public AvailabilityCache(@Qualifier("availabilityRedis") RedisEndpoint redis,ReservationAvailabilityService source,
            ObjectMapper json,JdbcTemplate db,MeterRegistry metrics,Clock clock,
            @Value("${availability.cache.enabled:false}") boolean enabled,
            @Value("${availability.cache.prefix:ct:availability:}") String prefix,
            @Value("${availability.cache.db-permits:1}") int permits) {
        if(permits<1) throw new IllegalArgumentException("Cache bypass needs a positive permit budget");
        this.redis=redis;this.source=source;this.json=json;this.db=db;this.metrics=metrics;this.clock=clock;
        this.cachedJson=json.readerFor(AvailabilityResponse.class).without(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        this.enabled=enabled;this.prefix=prefix;this.bypass=new Semaphore(permits);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startTracking() {
        if(enabled) {
            tracking=Boolean.TRUE.equals(db.queryForObject("SELECT enable_availability_tracking()",Boolean.class));
        }
    }

    private synchronized void prepare(RedisEndpoint.Session session) {
        if(!session.generation().equals(connectionGeneration)) {
            session.commands().set(prefix+"namespace",UUID.randomUUID().toString());
            connectionGeneration=session.generation();
        }
    }

    private record Read(AvailabilityResponse value,String wire) {}

    public AvailabilityResponse get(long store,LocalDate date,int party) {
        return read(store,date,party).value();
    }

    public Object httpBody(long store,LocalDate date,int party) {
        Read result=read(store,date,party);
        return result.wire()==null ? result.value() : result.wire();
    }

    private Read read(long store,LocalDate date,int party) {
        if(!enabled) return new Read(source.getAvailability(store,date,party),null);
        if(party<1) return new Read(source.getAvailability(store,date,party),null); // same 400 as the uncached path
        Query query=new Query(store,date,party);
        if(!tracking) return new Read(compute(query,null),null);
        Lookup lookup;
        try {
            lookup=redis.use(s->{
                prepare(s);
                String base=prefix+"s:"+store+":";
                String seed=nonce();
                List<String> values=s.commands().eval(LOOKUP,ScriptOutputType.MULTI,
                        new String[]{prefix+"namespace",base+"*",base+date},
                        base+date+":"+party+":",seed+"n",seed+"s",seed+"d");
                return new Lookup(values.get(0),values.get(1));
            });
        } catch(RedisException down) {
            return new Read(compute(query,null),null);
        }
        if(!lookup.json().isEmpty()) {
            try {
                AvailabilityResponse value=cachedJson.readValue(lookup.json());
                if(value!=null && value.storeId()!=null && value.storeId()==store && date.equals(value.date()) && value.partySize()==party && value.slots()!=null) {
                    metrics.counter("availability.cache.hit").increment();
                    return new Read(value,lookup.json());
                }
            } catch(JsonProcessingException bad) {
                metrics.counter("availability.cache.invalid").increment();
            }
        }
        return new Read(compute(query,lookup.key()),null);
    }

    private AvailabilityResponse compute(Query query,String key) {
        var flight=new Flight(query,key);
        var result=new CompletableFuture<AvailabilityResponse>();
        var leader=flights.putIfAbsent(flight,result);
        if(leader!=null) {
            metrics.counter("availability.cache.join").increment();
            try {
                return leader.get(5,TimeUnit.SECONDS);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                throw busy();
            } catch(TimeoutException e) {
                throw busy();
            } catch(ExecutionException e) {
                if(e.getCause() instanceof RuntimeException cause) throw cause;
                if(e.getCause() instanceof Error cause) throw cause;
                throw new CompletionException(e.getCause());
            }
        }
        boolean permit=false;
        try {
            AvailabilityResponse value;
            if(key==null) {
                if(!(permit=bypass.tryAcquire())) throw busy();
                metrics.counter("availability.cache.bypass").increment();
                value=source.getAvailability(query.store(),query.date(),query.party());
            } else {
                Instant start=clock.instant();
                metrics.counter("availability.cache.compute").increment();
                var day=source.cacheableDay(query.store(),query.date(),query.party(),start);
                value=day.response();
                if(clock.instant().isBefore(day.expiresAt())) {
                    try {
                        String body=json.writeValueAsString(value);
                        redis.use(s->{
                            prepare(s);
                            long left=day.expiresAt().toEpochMilli()-clock.millis();
                            return s.commands().eval("""
                                    local t=redis.call('TIME')
                                    local ttl=math.min(tonumber(ARGV[2]),tonumber(ARGV[3])-t[1]*1000-math.floor(t[2]/1000))
                                    if ttl>0 then redis.call('SET',KEYS[1],ARGV[1],'PX',ttl) end
                                    return ttl
                                    """,ScriptOutputType.INTEGER,new String[]{key},body,Long.toString(Math.min(2000,left)),
                                    Long.toString(day.expiresAt().toEpochMilli()));
                        });
                    } catch(RedisException | JsonProcessingException error) {
                        metrics.counter("availability.cache.write.failure").increment();
                    }
                }
            }
            result.complete(value);
            return value;
        } catch(RuntimeException | Error e) {
            result.completeExceptionally(e);
            throw e;
        } finally {
            if(permit) bypass.release();
            flights.remove(flight,result);
        }
    }

    private BusinessException busy() {
        metrics.counter("availability.cache.busy").increment();
        return new BusinessException(ErrorCode.AVAILABILITY_BUSY);
    }

    private String nonce() { return instance+"-"+Long.toUnsignedString(nonces.incrementAndGet(),36); }

    // Even if every cache reader is disabled or disconnected, expired slot data cannot accumulate an unlimited outbox.
    @Scheduled(fixedDelayString="${availability.cache.cleanup-ms:10000}",scheduler="waitingReceiptScheduler")
    public void discardObsoleteEvents() {
        db.update("""
                UPDATE availability_cache_invalidation SET visible_since=clock_timestamp()
                WHERE revision IN (SELECT revision FROM availability_cache_invalidation WHERE visible_since IS NULL
                    ORDER BY changed_at LIMIT 4096 FOR UPDATE SKIP LOCKED)
                """);
        db.update("""
                DELETE FROM availability_cache_invalidation WHERE revision IN (
                    SELECT revision FROM availability_cache_invalidation
                    WHERE visible_since<clock_timestamp()-interval '30 seconds' LIMIT 4096 FOR UPDATE SKIP LOCKED)
                """);
    }

    @Scheduled(fixedDelayString="${availability.cache.invalidation-delay-ms:100}",scheduler="waitingReceiptScheduler")
    public void drain() {
        if(!enabled) return;
        if(!tracking) { startTracking(); if(!tracking) return; }
        try {
            redis.use(s->{
                prepare(s);
                var dirty=db.query("""
                        SELECT store_id,coalesce(affected_scopes,ARRAY[scope]),revision
                        FROM availability_cache_invalidation ORDER BY changed_at LIMIT 128
                        """,(r,i)->new Dirty(r.getLong(1),(String[])r.getArray(2).getArray(),r.getObject(3,UUID.class)));
                if(!dirty.isEmpty()) {
                    var scopes=dirty.stream().flatMap(d -> java.util.Arrays.stream(d.scopes())
                            .map(scope -> prefix+"s:"+d.store()+":"+scope)).distinct().toArray(String[]::new);
                    String[] generations=java.util.Arrays.stream(scopes).map(k->nonce()).toArray(String[]::new);
                    s.commands().eval("for i,k in ipairs(KEYS) do redis.call('SET',k,ARGV[i],'EX',86400) end return 1",
                            ScriptOutputType.INTEGER,scopes,generations);
                    db.update("""
                            DELETE FROM availability_cache_invalidation WHERE revision IN (
                                SELECT revision FROM availability_cache_invalidation WHERE revision=ANY(?) FOR UPDATE SKIP LOCKED)
                            """,
                            new SqlArrayValue("uuid",dirty.stream().map(Dirty::revision).toArray()));
                }
                return true;
            });
        } catch(RedisException down) {
            metrics.counter("availability.cache.invalidation.retry").increment();
        }
    }
}
