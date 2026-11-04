package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.repository.AlimtalkOutboxRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AlimtalkOutboxRecorder {
    private final AlimtalkOutboxRepository outbox;
    private final WaitingRepository waitings;
    private final NotificationSettingService settings;
    private final Clock clock;
    @Value("${alimtalk.mode:simulation}")
    private String mode;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CustomerNotificationEvent event) {
        if (!settings.allows(event.memberId(), event.type())) return;
        var waiting = waitings.findById(event.targetId()).orElseThrow();
        if (!waiting.getMember().getId().equals(event.memberId())) {
            throw new IllegalArgumentException("Waiting notification member mismatch");
        }
        outbox.enqueue(waiting, OffsetDateTime.now(clock), mode);
    }
}
