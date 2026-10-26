package com.catchtable.waiting.dto;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.waiting.entity.WaitingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingCallNotificationTest {

    @Test
    @DisplayName("번호표·매장명·만료시각으로 호출 알림 이벤트를 만든다")
    void createEvent() {
        OffsetDateTime calledAt = OffsetDateTime.parse("2026-10-11T18:00:00+09:00");
        OffsetDateTime expiresAt = OffsetDateTime.parse("2026-10-11T18:10:00+09:00");

        CustomerNotificationEvent event =
                WaitingCallNotification.of(1L, 30L, 12, "스케줄 청담", calledAt, expiresAt);

        assertThat(event.memberId()).isEqualTo(1L);
        assertThat(event.type()).isEqualTo(NotificationType.WAITING_CALLED);
        assertThat(event.targetId()).isEqualTo(30L);
        assertThat(event.message()).isEqualTo("12번 고객님, 18:10까지 스케줄 청담에 입장해 주세요.");
        assertThat(event.payload()).isEqualTo(
                new WaitingCallPayload(WaitingStatus.CALLED, 12, "스케줄 청담", calledAt, expiresAt));
    }

    @Test
    @DisplayName("UTC로 읽힌 만료시각도 매장 현지 시각(Asia/Seoul)으로 표시한다")
    void messageUsesStoreZone() {
        OffsetDateTime calledAt = OffsetDateTime.parse("2026-10-11T09:00:00Z");
        OffsetDateTime expiresAt = OffsetDateTime.parse("2026-10-11T09:10:00Z");

        CustomerNotificationEvent event =
                WaitingCallNotification.of(1L, 30L, 12, "스케줄 청담", calledAt, expiresAt);

        assertThat(event.message()).isEqualTo("12번 고객님, 18:10까지 스케줄 청담에 입장해 주세요.");
    }

    @Test
    @DisplayName("만료시각이 자정을 넘기면 다음 날 시각으로 표시한다")
    void messageAfterMidnight() {
        OffsetDateTime calledAt = OffsetDateTime.parse("2026-10-11T23:55:00+09:00");
        OffsetDateTime expiresAt = OffsetDateTime.parse("2026-10-12T00:05:00+09:00");

        CustomerNotificationEvent event =
                WaitingCallNotification.of(1L, 30L, 7, "스케줄 청담", calledAt, expiresAt);

        assertThat(event.message()).isEqualTo("7번 고객님, 00:05까지 스케줄 청담에 입장해 주세요.");
    }
}
