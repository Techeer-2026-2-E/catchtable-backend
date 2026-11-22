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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** A bounded, multiplexed connection. Reconnect through Sentinel after disconnect or READONLY. */
public final class RedisEndpoint implements AutoCloseable {
    public record Session(UUID generation, RedisCommands<String,String> commands) {}
    private final RedisURI uri;
    private final RedisClient client;
    private final Duration connectionBudget;
    private final ReentrantLock reconnect = new ReentrantLock();
    private record Connected(StatefulRedisConnection<String,String> channel,Session session) {}
    private volatile Connected live;
    private long retryAt=System.nanoTime();

    public RedisEndpoint(ClientResources resources, String address, Duration timeout, String sentinelPassword) {
        if (timeout.toMillis() < 1) throw new IllegalArgumentException("Redis timeout must be at least 1 ms");
        uri=RedisURI.create(address);
        uri.setTimeout(timeout);
        uri.getSentinels().forEach(node -> {
            node.setTimeout(timeout);
            if (!sentinelPassword.isEmpty()) node.setPassword(sentinelPassword.toCharArray());
        });
        // Lettuce visits Sentinel seeds sequentially before establishing the data connection.
        connectionBudget=timeout.multipliedBy(1L+uri.getSentinels().size());
        client=RedisClient.create(resources,uri);
        client.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(128)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout)).build());
    }

    private Session connected() {
        Connected current = live;
        if (current != null && current.channel().isOpen()) return current.session();
        reconnect.lock();
        try {
            return connectLocked();
        } finally {
            reconnect.unlock();
        }
    }

    private Session connectLocked() {
        if (live!=null && live.channel().isOpen()) return live.session();
        if (live!=null) {
            live.channel().closeAsync();
            live=null;
        }
        if (System.nanoTime()-retryAt<0) throw new RedisException("Redis connection cooling down");
        var next=client.connectAsync(StringCodec.UTF8,uri);
        try {
            var channel=next.get(connectionBudget.toMillis(),TimeUnit.MILLISECONDS);
            live=new Connected(channel,new Session(UUID.randomUUID(),channel.sync()));
            return live.session();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            next.whenComplete((late,error)->{ if (late!=null) late.closeAsync(); });
            live=null;
            retryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
            throw new RedisException("Redis connection unavailable",e);
        }
    }

    public <T> T use(Function<Session,T> operation) {
        Session active=connected();
        try {
            return operation.apply(active);
        } catch (RedisException e) {
            reconnect.lock();
            try {
                if (live!=null && live.session()==active) {
                    var failed=live;
                    live=null;
                    failed.channel().closeAsync();
                    retryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
                }
            } finally {
                reconnect.unlock();
            }
            throw e;
        }
    }

    @Override public void close() {
        reconnect.lock();
        try {
            var previous=live;
            live=null;
            if(previous!=null && previous.channel().isOpen()) previous.channel().closeAsync();
            client.shutdown(Duration.ZERO,Duration.ofSeconds(2));
        } finally {
            reconnect.unlock();
        }
    }
}
