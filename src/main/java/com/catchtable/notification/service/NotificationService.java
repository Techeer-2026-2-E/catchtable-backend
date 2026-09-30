package com.catchtable.notification.service;


import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.repository.SseEmitterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final long TIMEOUT_MILLIS=30*60*1000L;
    private final SseEmitterRepository emitterRepository;

    public SseEmitter subscribe(Long memberId)
    {
        SseEmitter emitter=new SseEmitter(TIMEOUT_MILLIS);
        emitterRepository.save(memberId,emitter);

        emitter.onCompletion(() -> emitterRepository.delete(memberId, emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitterRepository.delete(memberId, emitter));

        send(memberId, emitter, SseEmitter.event().name("connect").data("connected"));

        return emitter;
    }
    public void send(CustomerNotificationEvent event)
    {
        NotificationMessage message=NotificationMessage.from(event, LocalDateTime.now());
        for(SseEmitter emitter:emitterRepository.findAllByMemberId(event.memberId()))
        {
            send(event.memberId(),emitter,SseEmitter.event()
                    .id(message.eventId())
                    .name(message.type().name())
                    .data(message));
        }
    }

    private void send(Long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event)
    {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            emitterRepository.delete(memberId, emitter);
        }
    }
}
