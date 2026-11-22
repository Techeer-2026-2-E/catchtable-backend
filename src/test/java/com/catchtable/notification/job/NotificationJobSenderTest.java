package com.catchtable.notification.job;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobResult;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.notification.service.NotificationSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NotificationJobSenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SseEmitterRepository repository = new SseEmitterRepository();
    // 항상 수신 허용
    private final NotificationSettingService allowAll = mock(NotificationSettingService.class, invocation -> true);
    private final NotificationJobSender sender = senderWith(allowAll);

    @Test
    @DisplayName("회원의 열린 연결마다 보내고 다른 회원에게는 보내지 않는다")
    void sendsToEveryConnectionOfMember() throws Exception {
        SseEmitter tab1 = mock(SseEmitter.class);
        SseEmitter tab2 = mock(SseEmitter.class);
        SseEmitter otherMember = mock(SseEmitter.class);
        repository.save(1L, tab1);
        repository.save(1L, tab2);
        repository.save(2L, otherMember);

        assertThat(sender.sendNotify(confirmed(1L))).isEqualTo(NotificationJobResult.SENT);

        verify(tab1).send(any(SseEmitter.SseEventBuilder.class));
        verify(tab2).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherMember, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("저장해 둔 eventId와 알림 종류를 이벤트 id·이름으로 보내고, 다시 보내도 eventId가 같다")
    void sendsStoredEventIdAndSameIdOnRetry() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        repository.save(1L, emitter);
        NotificationJob job = confirmed(1L);
        String eventId = mapper.readTree(job.getPayload()).get("eventId").asText();

        sender.sendNotify(job);
        sender.sendNotify(job); // lease가 지나 다시 가져간 경우

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(captor.capture());
        for (SseEmitter.SseEventBuilder sent : captor.getAllValues()) {
            String raw = sent.build().stream()
                    .map(part -> String.valueOf(part.getData()))
                    .collect(Collectors.joining());
            assertThat(raw).contains("id:" + eventId + "\n");
            assertThat(raw).contains("event:RESERVATION_CONFIRMED\n");
        }
    }

    @Test
    @DisplayName("끊긴 연결(IOException)은 지우고 나머지 연결에는 보낸다")
    void removesBrokenConnection() throws Exception {
        SseEmitter broken = mock(SseEmitter.class);
        SseEmitter alive = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        repository.save(1L, broken);
        repository.save(1L, alive);

        assertThat(sender.sendNotify(confirmed(1L))).isEqualTo(NotificationJobResult.SENT);

        verify(alive).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(repository.findAllByMemberId(1L)).containsExactly(alive);
    }

    @Test
    @DisplayName("이미 끝난 연결(IllegalStateException)도 지우고, 보낸 곳이 없으면 NO_CONNECTION")
    void removesCompletedConnection() throws Exception {
        SseEmitter completed = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(completed).send(any(SseEmitter.SseEventBuilder.class));
        repository.save(1L, completed);

        assertThat(sender.sendNotify(confirmed(1L))).isEqualTo(NotificationJobResult.NO_CONNECTION);
        assertThat(repository.findAllByMemberId(1L)).isEmpty();
    }

    @Test
    @DisplayName("연결이 없으면 NO_CONNECTION")
    void noSubscriber() throws Exception {
        assertThat(sender.sendNotify(confirmed(99L))).isEqualTo(NotificationJobResult.NO_CONNECTION);
    }

    @Test
    @DisplayName("보내는 순간 설정이 꺼져 있으면 보내지 않고 SETTING_DISABLED")
    void settingDisabled() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        repository.save(1L, emitter);
        NotificationJobSender denying = senderWith(mock(NotificationSettingService.class)); // allows → false

        assertThat(denying.sendNotify(confirmed(1L))).isEqualTo(NotificationJobResult.SETTING_DISABLED);
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private NotificationJobSender senderWith(NotificationSettingService settings) {
        return new NotificationJobSender(repository, settings, mock(NotificationJobTargetQuery.class), mapper, CLOCK);
    }

    // recorder가 저장하는 것과 같은 방식으로 job을 만든다
    private NotificationJob confirmed(Long memberId) throws Exception {
        var event = new CustomerNotificationEvent(memberId, NotificationType.RESERVATION_CONFIRMED, 10L, "예약이 확정되었습니다");
        String payload = mapper.writeValueAsString(NotificationMessage.from(event, LocalDateTime.now(CLOCK)));
        return NotificationJob.notify("test:" + memberId, memberId, NotificationType.RESERVATION_CONFIRMED,
                10L, null, payload, OffsetDateTime.now(CLOCK));
    }
}
