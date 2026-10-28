package com.catchtable.waiting.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingExpireTest {

    private static final OffsetDateTime CALLED_AT = OffsetDateTime.of(2030, 1, 11, 0, 5, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime EXPIRES_AT = CALLED_AT.plusMinutes(10);

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"CALLED", "CONFIRMED"})
    void expiresCalledTeamAfterDeadline(WaitingStatus status) {
        Waiting waiting = called();
        ReflectionTestUtils.setField(waiting, "status", status);
        OffsetDateTime now = EXPIRES_AT.plusNanos(1);

        WaitingEndHistory history = waiting.expire(now);

        assertThat(waiting.getStatus()).isEqualTo(WaitingStatus.NO_SHOW);
        assertThat(waiting.getEndReason()).isEqualTo(WaitingEndReason.AUTO_EXPIRED);
        assertThat(waiting.getEndDetail()).isEqualTo("호출 후 응답·입장 기한 만료");
        assertThat(waiting.getEndedAt()).isEqualTo(now);
        assertThat(history.getPreviousStatus()).isEqualTo(status);
        assertThat(history.getReason()).isEqualTo(WaitingEndReason.AUTO_EXPIRED);
        assertThat(history.getActorId()).isNull();
    }

    @Test
    void keepsCalledTeamUntilDeadline() {
        Waiting waiting = called();
        assertThat(waiting.expire(EXPIRES_AT.minusSeconds(1))).isNull();
        // 경계 시각: 응답 허용과 동일
        assertThat(waiting.expire(EXPIRES_AT)).isNull();
        assertThat(waiting.getStatus()).isEqualTo(WaitingStatus.CALLED);
    }

    @Test
    void recallExtendsDeadline() {
        Waiting waiting = called();
        waiting.call(EXPIRES_AT.minusMinutes(1), 10);
        assertThat(waiting.expire(EXPIRES_AT.plusMinutes(1))).isNull();
        assertThat(waiting.getStatus()).isEqualTo(WaitingStatus.CALLED);
    }

    @Test
    void ignoresWaitingSeatedAndCanceledTeams() {
        OffsetDateTime later = EXPIRES_AT.plusHours(1);
        Waiting waiting = Waiting.create(null, null, CALLED_AT.toLocalDate(), 2, 1);
        assertThat(waiting.expire(later)).isNull();
        assertThat(waiting.getStatus()).isEqualTo(WaitingStatus.WAITING);

        Waiting seated = called();
        seated.enter(EXPIRES_AT.plusMinutes(1));
        assertThat(seated.expire(later)).isNull();
        assertThat(seated.getStatus()).isEqualTo(WaitingStatus.SEATED);

        Waiting canceled = called();
        canceled.cancelByCustomer(1L, EXPIRES_AT.minusMinutes(1));
        assertThat(canceled.expire(later)).isNull();
        assertThat(canceled.getEndReason()).isEqualTo(WaitingEndReason.CUSTOMER_CANCELED);
    }

    @Test
    void ignoresAlreadyEndedNoShow() {
        Waiting waiting = called();
        waiting.expire(EXPIRES_AT.plusMinutes(1));
        assertThat(waiting.expire(EXPIRES_AT.plusMinutes(2))).isNull();
    }

    private static Waiting called() {
        Waiting waiting = Waiting.create(null, null, LocalDate.of(2030, 1, 11), 2, 1);
        waiting.call(CALLED_AT, 10);
        return waiting;
    }
}
