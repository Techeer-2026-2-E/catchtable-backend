package com.catchtable.waiting.entity;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingRecallTest {

    private static final OffsetDateTime CALLED_AT = OffsetDateTime.of(2030, 1, 11, 0, 5, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime EXPIRES_AT = CALLED_AT.plusMinutes(10);

    @Test
    @DisplayName("호출 중·만료 전이면 재전송 횟수와 시각만 바뀐다")
    void recallChangesOnlyCountAndTime() {
        Waiting waiting = called();
        waiting.respond(CALLED_AT.plusMinutes(1));
        OffsetDateTime respondedAt = waiting.getRespondedAt();

        waiting.recall(CALLED_AT.plusMinutes(3));
        waiting.recall(EXPIRES_AT.minusNanos(1));

        assertThat(waiting.getRecallCount()).isEqualTo(2);
        assertThat(waiting.getLastRecalledAt()).isEqualTo(EXPIRES_AT.minusNanos(1));
        assertThat(waiting.getStatus()).isEqualTo(WaitingStatus.CALLED);
        assertThat(waiting.getCallCount()).isEqualTo(1);
        assertThat(waiting.getCalledAt()).isEqualTo(CALLED_AT);
        assertThat(waiting.getCallExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(waiting.getRespondedAt()).isEqualTo(respondedAt);
        assertThat(waiting.getArrivalIntent()).isEqualTo(ArrivalIntent.COMING);
    }

    @Test
    @DisplayName("만료시각 이후에는 재전송할 수 없다")
    void cannotRecallAtOrAfterExpiry() {
        Waiting waiting = called();
        assertNotRecallable(waiting, EXPIRES_AT);
        assertNotRecallable(waiting, EXPIRES_AT.plusMinutes(1));
        assertThat(waiting.getRecallCount()).isZero();
        assertThat(waiting.getLastRecalledAt()).isNull();
    }

    @Test
    @DisplayName("호출 전 대기 중이면 재전송할 수 없다")
    void cannotRecallUncalledTeam() {
        Waiting waiting = Waiting.create(null, null, LocalDate.of(2030, 1, 11), 2, 1);
        assertNotRecallable(waiting, CALLED_AT);
        assertThat(waiting.getRecallCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"CONFIRMED", "SEATED", "CANCELED", "NO_SHOW"})
    @DisplayName("착석·종료 등 호출 중이 아니면 재전송할 수 없다")
    void cannotRecallNonCalledTeam(WaitingStatus status) {
        Waiting waiting = called();
        ReflectionTestUtils.setField(waiting, "status", status);
        assertNotRecallable(waiting, CALLED_AT.plusMinutes(1));
        assertThat(waiting.getStatus()).isEqualTo(status);
        assertThat(waiting.getRecallCount()).isZero();
    }

    private static void assertNotRecallable(Waiting waiting, OffsetDateTime now) {
        assertThatThrownBy(() -> waiting.recall(now)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WAITING_NOT_RECALLABLE));
    }

    private static Waiting called() {
        Waiting waiting = Waiting.create(null, null, LocalDate.of(2030, 1, 11), 2, 1);
        waiting.call(CALLED_AT, 10);
        return waiting;
    }
}
