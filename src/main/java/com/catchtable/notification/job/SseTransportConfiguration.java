package com.catchtable.notification.job;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.EOFException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration(proxyBeanMethods = false)
public class SseTransportConfiguration {
    private static final String SUBSCRIPTION = SseTransportConfiguration.class.getName() + ".subscription";

    @Bean
    ServletContextInitializer sseWriteReadiness(@Value("${notification.job.write-timeout:500ms}") Duration timeout,
                                               MeterRegistry metrics) {
        var opened = metrics.counter("notification.sse.connections.ready");
        return context -> {
            var registration = context.addFilter("sseWriteReadiness", new OncePerRequestFilter() {
                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return !"GET".equals(request.getMethod());
                }

                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    var stream = new StreamingResponse(request, response, timeout);
                    request.setAttribute(SUBSCRIPTION, stream);
                    chain.doFilter(request, stream);
                    if (request.isAsyncStarted() && stream.output != null) {
                        stream.activate(opened::increment);
                    }
                }
            });
            registration.setAsyncSupported(true);
            registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false,
                    "/api/user/notifications/*", "/api/owner/stores/*");
        };
    }

    /** 실제 HTTP 구독은 첫 쓰기 가능 콜백에서 등록한다. 직접 사용하는 메모리 emitter는 즉시 등록할 수 있다. */
    public static void onSubscriptionReady(Runnable register, Runnable remove) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(SUBSCRIPTION) instanceof StreamingResponse stream) {
            stream.ready.thenRun(() -> {
                register.run();
                stream.closed.thenRun(remove);
            });
        } else {
            register.run();
        }
    }

    private static final class StreamingResponse extends HttpServletResponseWrapper {
        private final HttpServletRequest request;
        private final Duration timeout;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private volatile SseServletOutput output;

        private StreamingResponse(HttpServletRequest request, HttpServletResponse response, Duration timeout) {
            super(response);
            this.request = request;
            this.timeout = timeout;
        }

        private void activate(Runnable opened) {
            var async = request.getAsyncContext();
            var active = new AtomicBoolean(true);
            async.addListener(new AsyncListener() {
                private void end(Throwable reason) {
                    active.set(false);
                    output.onError(reason);
                    closed.complete(null);
                }
                @Override public void onComplete(AsyncEvent event) { end(new EOFException("SSE 요청 종료")); }
                @Override public void onTimeout(AsyncEvent event) { end(new SocketTimeoutException("SSE 구독 만료")); }
                @Override public void onError(AsyncEvent event) { end(event.getThrowable()); }
                @Override public void onStartAsync(AsyncEvent event) { end(new EOFException("SSE 요청 재시작")); }
            });
            output.arm(() -> {
                opened.run();
                ready.complete(null);
            }, () -> {
                if (active.compareAndSet(true, false)) {
                    try {
                        async.complete();
                    } catch (IllegalStateException alreadyCompleted) {
                        // 컨테이너가 동시에 이 async 주기를 끝냈다.
                    }
                }
            });
        }

        @Override
        public synchronized ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                String type = getContentType();
                if (!request.isAsyncStarted() || type == null || !type.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                    return super.getOutputStream();
                }
                output = new SseServletOutput(super.getOutputStream(), timeout);
            }
            return output;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (output != null) output.flush();
            else super.flushBuffer();
        }
    }
}
