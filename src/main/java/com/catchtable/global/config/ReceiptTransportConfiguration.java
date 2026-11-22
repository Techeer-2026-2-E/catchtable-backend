package com.catchtable.global.config;

import org.apache.catalina.Lifecycle;
import org.apache.coyote.AbstractProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ReceiptTransportConfiguration {
    static int httpLimit(long heapBytes,int configured) {
        if(configured<1) throw new IllegalArgumentException("HTTP admission limit must be positive");
        return (int)Math.max(1,Math.min(Math.min(2048,configured),heapBytes/(512*1024)));
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> receiptTransport(
            @Value("${waiting.intake.async.enabled:false}") boolean async,
            @Value("${waiting.intake.async.max-in-flight:2147483647}") int configuredLimit,
            @Value("${diagnostics.tomcat-timing:false}") boolean timed) {
        return factory -> {
            if(!async) return;
            factory.addConnectorCustomizers(connector -> {
                if (!connector.setProperty("socket.appReadBufSize","2048")
                        || !connector.setProperty("socket.appWriteBufSize","2048")) {
                    throw new IllegalStateException("Could not configure Tomcat NIO socket buffers");
                }
                // Queue socket tasks before they acquire a servlet processor. A virtual
                // request can park on a grouped commit without monopolizing a carrier.
                int maximum=httpLimit(Runtime.getRuntime().maxMemory(),configuredLimit);
                var workers=new ThreadPoolExecutor(maximum,maximum,60,TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),Thread.ofVirtual().name("receipt-http-",0).factory());
                workers.allowCoreThreadTimeOut(true);
                var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();
                connector.addLifecycleListener(event->{
                    if(Lifecycle.BEFORE_START_EVENT.equals(event.getType()))
                        protocol.setExecutor(task->workers.execute(timed ? IntakeTimingConfiguration.timed(task) : task));
                    else if(Lifecycle.AFTER_STOP_EVENT.equals(event.getType())) workers.shutdown();
                });
            });
        };
    }
}
