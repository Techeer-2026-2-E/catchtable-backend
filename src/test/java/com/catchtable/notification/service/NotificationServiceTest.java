package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NotificationServiceTest {

    private final SseEmitterRepository repository = new SseEmitterRepository();
    private final NotificationService service = new NotificationService(repository);

    private CustomerNotificationEvent confirmed(Long memberId) {
        return new CustomerNotificationEvent(memberId, NotificationType.RESERVATION_CONFIRMED, 10L, "예약이 확정되었습니다");
    }

    @Test
    @DisplayName("구독하면 연결이 회원 id로 등록된다")
    void subscribe() {
        SseEmitter emitter = service.subscribe(1L);

        assertThat(repository.findAllByMemberId(1L)).containsExactly(emitter);
    }

    @Test
    @DisplayName("회원의 모든 연결로 보내고, 다른 회원에게는 보내지 않는다")
    void sendToAllEmittersOfMember() throws IOException {
        SseEmitter tab1 = mock(SseEmitter.class);
        SseEmitter tab2 = mock(SseEmitter.class);
        SseEmitter otherMember = mock(SseEmitter.class);
        repository.save(1L, tab1);
        repository.save(1L, tab2);
        repository.save(2L, otherMember);

        service.send(confirmed(1L));

        verify(tab1).send(any(SseEmitter.SseEventBuilder.class));
        verify(tab2).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherMember, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("이벤트 id와 알림 종류를 이벤트 이름으로 담아 보낸다")
    void eventIdAndName() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        repository.save(1L, emitter);

        service.send(confirmed(1L));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        String raw = captor.getValue().build().stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());
        assertThat(raw).containsPattern("id:[0-9a-f-]{36}\n");
        assertThat(raw).contains("event:RESERVATION_CONFIRMED\n");
    }

    @Test
    @DisplayName("전송에 실패한 연결은 제거하고 나머지 연결에는 계속 보낸다")
    void removeBrokenEmitter() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        SseEmitter alive = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        repository.save(1L, broken);
        repository.save(1L, alive);

        service.send(confirmed(1L));

        verify(alive).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(repository.findAllByMemberId(1L)).containsExactly(alive);
    }

    @Test
    @DisplayName("이미 끝난 연결(IllegalStateException)도 제거한다")
    void removeCompletedEmitter() throws IOException {
        SseEmitter completed = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(completed).send(any(SseEmitter.SseEventBuilder.class));
        repository.save(1L, completed);

        service.send(confirmed(1L));

        assertThat(repository.findAllByMemberId(1L)).isEmpty();
    }

    @Test
    @DisplayName("연결이 없는 회원에게 보내면 아무 일도 일어나지 않는다")
    void noSubscriber() {
        assertThatCode(() -> service.send(confirmed(99L))).doesNotThrowAnyException();
    }
}
