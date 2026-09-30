package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 실제 PostgreSQL(docker compose)로 트랜잭션을 열어 커밋·롤백 시점을 확인한다
@SpringBootTest
class NotificationEventListenerTest {

    private static final Long MEMBER_ID = 900_001L;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SseEmitterRepository repository;

    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = mock(SseEmitter.class);
        repository.save(MEMBER_ID, emitter);
    }

    @AfterEach
    void tearDown() {
        repository.delete(MEMBER_ID, emitter);
    }

    private CustomerNotificationEvent event() {
        return new CustomerNotificationEvent(MEMBER_ID, NotificationType.RESERVATION_CANCELED, 10L, "예약이 취소되었습니다");
    }

    @Test
    @DisplayName("트랜잭션이 커밋되면 알림을 보낸다")
    void sendAfterCommit() throws IOException {
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event()));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 알림을 보내지 않는다")
    void noSendOnRollback() throws IOException {
        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(event());
            status.setRollbackOnly();
        });

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("커밋 전에는 보내지 않는다")
    void notBeforeCommit() throws IOException {
        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(event());
            try {
                verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("트랜잭션 밖에서 발행해도 바로 보낸다")
    void sendWithoutTransaction() throws IOException {
        publisher.publishEvent(event());

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }
}
