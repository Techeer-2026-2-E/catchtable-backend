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

class WaitingDeferTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 11);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2030, 1, 11, 0, 5, 0, 0, ZoneOffset.ofHours(9));

    @Test
    @DisplayName("신규 웨이팅의 정렬 순서는 대기번호와 같다")
    void queueOrderStartsAtWaitNumber() {
        Waiting waiting = waiting(3);
        assertThat(waiting.getQueueOrder()).isEqualTo(3);
        assertThat(waiting.getDeferCount()).isZero();
    }

    @Test
    @DisplayName("연기하면 뒤 팀과 정렬 순서만 맞바꾸고 대기번호는 그대로다")
    void deferSwapsQueueOrderOnly() {
        Waiting first = waiting(1);
        Waiting second = waiting(2);

        WaitingDeferHistory history = first.deferBehind(second, 1, 2, 7L, NOW);

        assertThat(first.getQueueOrder()).isEqualTo(2);
        assertThat(second.getQueueOrder()).isEqualTo(1);
        assertThat(first.getWaitNumber()).isEqualTo(1);
        assertThat(second.getWaitNumber()).isEqualTo(2);
        assertThat(first.getDeferCount()).isEqualTo(1);
        assertThat(second.getDeferCount()).isZero();
        assertThat(history.getBeforePosition()).isEqualTo(1);
        assertThat(history.getAfterPosition()).isEqualTo(2);
        assertThat(history.getActorId()).isEqualTo(7L);
        assertThat(history.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("2회 연기 후 3번째 연기는 거부한다")
    void rejectsThirdDefer() {
        Waiting waiting = waiting(1);
        waiting.deferBehind(waiting(2), 1, 2, 7L, NOW);
        waiting.deferBehind(waiting(3), 2, 3, 7L, NOW);

        assertFailure(() -> waiting.deferBehind(waiting(4), 3, 4, 7L, NOW), ErrorCode.WAITING_DEFER_LIMIT_EXCEEDED);
        assertThat(waiting.getDeferCount()).isEqualTo(2);
        assertThat(waiting.getQueueOrder()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = "WAITING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("대기 중이 아니면 연기할 수 없다")
    void onlyWaitingTeamCanDefer(WaitingStatus status) {
        Waiting waiting = waiting(1);
        Waiting next = waiting(2);
        ReflectionTestUtils.setField(waiting, "status", status);

        assertFailure(() -> waiting.deferBehind(next, 1, 2, 7L, NOW), ErrorCode.WAITING_NOT_DEFERRABLE);
        assertThat(waiting.getQueueOrder()).isEqualTo(1);
        assertThat(next.getQueueOrder()).isEqualTo(2);
        assertThat(waiting.getDeferCount()).isZero();
    }

    private static void assertFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private static Waiting waiting(int number) {
        return Waiting.create(null, null, TODAY, 2, number);
    }
}
