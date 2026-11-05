package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AlimtalkOutboxListener {
    private final AlimtalkOutboxRecorder recorder;
    @Value("${alimtalk.enabled:false}")
    private boolean enabled;

    // 커밋 후 SSE와 달리 발행 즉시 같은 업무 트랜잭션에 저장한다.
    @EventListener
    public void handle(CustomerNotificationEvent event) {
        if (enabled && event.type() == NotificationType.WAITING_CALLED) recorder.record(event);
    }
}
