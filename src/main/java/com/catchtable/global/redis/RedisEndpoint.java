package com.catchtable.global.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.ClientResources;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** A bounded, multiplexed connection. Reconnect through Sentinel after disconnect or READONLY. */
public final class RedisEndpoint implements AutoCloseable {
    public record Session(UUID generation, RedisCommands<String,String> commands) {}
    private final RedisURI uri;
    private final RedisClient client;
    private final Duration timeout;
    private volatile StatefulRedisConnection<String,String> connection;
    private volatile Session session;
    private long retryAt;

    public RedisEndpoint(ClientResources resources, String address, Duration timeout, String sentinelPassword) {
        this.timeout=timeout;
        uri=RedisURI.create(address);
        uri.setTimeout(timeout);
        if(!sentinelPassword.isEmpty()) uri.getSentinels().forEach(s->s.setPassword(sentinelPassword.toCharArray()));
        client=RedisClient.create(resources,uri);
        client.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(128)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout)).build());
    }

    private synchronized Session connected() {
        if (session!=null && connection.isOpen()) return session;
        if (System.nanoTime()<retryAt) throw new RedisException("Redis connection cooling down");
        var next=client.connectAsync(StringCodec.UTF8,uri);
        try {
            connection=next.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            session=new Session(UUID.randomUUID(),connection.sync());
            return session;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            next.whenComplete((late,error)->{ if (late!=null) late.closeAsync(); });
            session=null;
            retryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
            throw new RedisException("Redis connection unavailable",e);
        }
    }

    public <T> T use(Function<Session,T> operation) {
        Session active=connected();
        try {
            return operation.apply(active);
        } catch (RedisException e) {
            synchronized (this) {
                if (session==active) {
                    session=null;
                    connection.closeAsync();
                    retryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
                }
            }
            throw e;
        }
    }

    @Override public synchronized void close() {
        if(connection!=null) connection.closeAsync();
        client.shutdown(Duration.ZERO,Duration.ofSeconds(2));
    }
}
