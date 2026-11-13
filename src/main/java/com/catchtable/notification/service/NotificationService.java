package com.catchtable.notification.service;


import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.durability.WaitingSseJournal;
import com.catchtable.notification.repository.SseEmitterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final long TIMEOUT_MILLIS=30*60*1000L;
    private final SseEmitterRepository emitterRepository;
    private final NotificationSettingService settingService;
    private final WaitingSseJournal waitingSseJournal;

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
        if (WaitingSseJournal.isWaiting(event)) {
            waitingSseJournal.enqueue(event);
            return;
        }
        if (!settingService.allows(event.memberId(), event.type())) return;
        NotificationMessage message = NotificationMessage.from(event, LocalDateTime.now());
        for (SseEmitter emitter : List.copyOf(emitterRepository.findAllByMemberId(event.memberId()))) {
            send(event.memberId(), emitter, SseEmitter.event()
                    .id(message.eventId()).name(message.type().name()).data(message));
        }
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
