package com.catchtable.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.core.StandardThreadExecutor;
import org.apache.coyote.AbstractProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Opt-in lab instrumentation. Use the same Tomcat executor implementation and budgets in both arms. */
@Configuration
@ConditionalOnProperty(name="diagnostics.tomcat-timing",havingValue="true")
public class IntakeTimingConfiguration {
    private record Timing(long queuedMs,long startedMs,long waitUs) {}
    private static final ThreadLocal<Timing> DISPATCH=new ThreadLocal<>();

    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> timedConnector() {
        return factory->factory.addConnectorCustomizers(c->{
            var protocol=(AbstractProtocol<?>)c.getProtocolHandler();
            var pool=new StandardThreadExecutor() {
                @Override public void execute(Runnable task) {
                    long queued=System.nanoTime(), epoch=System.currentTimeMillis();
                    super.execute(()->{
                        DISPATCH.set(new Timing(epoch,System.currentTimeMillis(),
                                TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-queued)));
                        try { task.run(); } finally { DISPATCH.remove(); }
                    });
                }
            };
            pool.setName("intake-lab-"+c.getPort());
            c.addLifecycleListener(e->{
                try {
                    if(Lifecycle.BEFORE_START_EVENT.equals(e.getType())) {
                        pool.setMaxThreads(protocol.getMaxThreads());
                        pool.setMinSpareThreads(protocol.getMinSpareThreads());
                        pool.start();
                        protocol.setExecutor(pool);
                    } else if(Lifecycle.AFTER_STOP_EVENT.equals(e.getType())) {
                        pool.stop();pool.destroy();
                    }
                } catch(LifecycleException ex) { throw new IllegalStateException(ex); }
            });
        });
    }

    @Bean OncePerRequestFilter intakeDispatchTimes() {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
                    throws ServletException,IOException {
                Timing t=DISPATCH.get();
                if(t!=null) {
                    req.setAttribute("ctQueued",t.queuedMs());
                    req.setAttribute("ctStarted",t.startedMs());
                    req.setAttribute("ctExecUs",t.waitUs());
                }
                chain.doFilter(req,res);
            }
        };
    }
}
