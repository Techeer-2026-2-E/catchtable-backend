package com.catchtable.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** I-only opt-in boundary instrumentation; never packaged or active in comparison runs. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "probe.sse.enabled", havingValue = "true")
public class IntegrationSseProbe {
    private static final Map<Long, Set<String>> LEASES = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static synchronized void record(Path dir, String phase) {
        try {
            var row = Map.of("phase", phase, "nanoTime", System.nanoTime(),
                    "thread", Thread.currentThread().getName(),
                    "transaction", TransactionSynchronizationManager.isActualTransactionActive(),
                    "connectionLeases", LEASES.getOrDefault(Thread.currentThread().threadId(), Set.of()).size());
            Files.writeString(dir.resolve("boundaries.jsonl"), JSON.writeValueAsString(row)+"\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) { throw new IllegalStateException("I boundary evidence unavailable", ex); }
    }

    @Bean static BeanPostProcessor integrationBoundaryWrappers(Environment environment) {
        Path dir = Path.of(environment.getRequiredProperty("probe.sse.directory"));
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource) {
                    var proxy = new ProxyFactory(bean);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        Object value = call.proceed();
                        if (!call.getMethod().getName().equals("getConnection") || !(value instanceof java.sql.Connection connection)) return value;
                        long owner = Thread.currentThread().threadId(); String lease = UUID.randomUUID().toString();
                        LEASES.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(lease);
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                                (wrapper, method, args) -> {
                                    try {
                                        Object result = method.invoke(connection, args);
                                        if (method.getName().equals("close")) LEASES.get(owner).remove(lease);
                                        return result;
                                    } catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                                });
                    });
                    return proxy.getProxy();
                }
                if (bean instanceof com.catchtable.payment.client.TossPaymentsClient) {
                    var proxy = new ProxyFactory(bean);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        record(dir, "pg.enter."+call.getMethod().getName());
                        return call.proceed();
                    });
                    return proxy.getProxy();
                }
                if (bean instanceof com.catchtable.notification.repository.SseEmitterRepository) {
                    var proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        if (call.getMethod().getName().equals("delete") && call.getArguments()[1] instanceof WrappedEmitter wrapped)
                            call.getArguments()[1] = wrapped.delegate;
                        Object value = call.proceed();
                        if (call.getMethod().getName().equals("find") && value instanceof SseEmitter emitter)
                            return new WrappedEmitter(emitter, dir);
                        return value;
                    });
                    return proxy.getProxy();
                }
                return bean;
            }
        };
    }

    private static class WrappedEmitter extends SseEmitter {
        private final SseEmitter delegate;
        private final Path directory;
        WrappedEmitter(SseEmitter delegate, Path directory) { this.delegate=delegate; this.directory=directory; }
        @Override public void send(SseEventBuilder event) throws IOException {
            record(directory, "sse.send.enter");
            delegate.send(event);
            record(directory, "sse.wire.return");
            if (Files.deleteIfExists(directory.resolve("arm-after-send"))) {
                Files.writeString(directory.resolve("after-send-entered"), Thread.currentThread().getName());
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (!Files.exists(directory.resolve("release-after-send"))) {
                    if (System.nanoTime()>deadline) throw new IllegalStateException("I send barrier expired without owned kill");
                    try { Thread.sleep(10); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("I send barrier interrupted",ex); }
                }
            }
        }
    }
}
