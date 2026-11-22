package com.catchtable.notification.service;


import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.notification.job.SseTransportConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

// SSE 연결 받기만 한다. 알림 전송은 NotificationJobWorker가 한다
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final long TIMEOUT_MILLIS=30*60*1000L;
    private final SseEmitterRepository emitterRepository;

    public SseEmitter subscribe(Long memberId)
    {
        SseEmitter emitter=new SseEmitter(TIMEOUT_MILLIS);
        Runnable remove = () -> emitterRepository.delete(memberId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> remove.run());

        SseTransportConfiguration.onSubscriptionReady(() -> emitterRepository.save(memberId, emitter), remove);
        send(memberId, emitter, SseEmitter.event().name("connect").data("connected"));

        return emitter;
    }

    private boolean send(Long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event)
    {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            emitterRepository.delete(memberId, emitter);
            return false;
        }
    }
}
