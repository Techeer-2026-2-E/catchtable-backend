package com.catchtable.notification.service;

import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.MemberNotificationSettingRepository;
import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRequiredFirstTest {

    private final MemberNotificationSettingRepository settingRepository = mock(MemberNotificationSettingRepository.class);
    private final NotificationSettingService settingService =
            new NotificationSettingService(settingRepository, mock(MemberRepository.class), Clock.systemUTC());
    private final SseEmitterRepository emitterRepository = new SseEmitterRepository();
    private final NotificationService notificationService = new NotificationService(emitterRepository, settingService, mock(com.catchtable.notification.durability.WaitingSseJournal.class));

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"WAITING_CALLED", "WAITING_EXPIRED", "WAITING_CANCELED", "RESERVATION_CANCELED", "WAITING_CHANGED"})
    @DisplayName("필수 알림 설정은 조회 없이 허용되며 예약 전달 경로는 보존된다")
    void requiredSentWithoutLookup(NotificationType type) throws IOException {
        when(settingRepository.findById(any())).thenThrow(new DataAccessResourceFailureException("DB 장애"));
        SseEmitter emitter = mock(SseEmitter.class);
        emitterRepository.save(1L, emitter);

        assertThat(settingService.allows(1L, type)).isTrue();
        if (type == NotificationType.RESERVATION_CANCELED) {
            notificationService.send(new CustomerNotificationEvent(1L, type, 1L, "알림"));
            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }
        verify(settingRepository, never()).findById(any());
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"RESERVATION_CONFIRMED", "RESERVATION_CHANGED", "WAITING_POSITION_CHANGED"})
    @DisplayName("선택 알림은 설정을 조회해 판단한다")
    void optionalLooksUpSetting(NotificationType type) {
        when(settingRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(settingService.allows(1L, type)).isTrue();
        verify(settingRepository).findById(1L);

        when(settingRepository.findById(2L)).thenThrow(new DataAccessResourceFailureException("DB 장애"));
        assertThatThrownBy(() -> settingService.allows(2L, type)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
