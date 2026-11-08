package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.catchtable.waiting.dto.OwnerWaitingResponse;
import com.catchtable.waiting.dto.WaitingDeferResponse;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.repository.WaitingDeferHistoryRepository;
import com.catchtable.waiting.WaitingDeferTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

@WaitingDeferTestContext
@RecordApplicationEvents
class WaitingDeferServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 11);

    @Autowired OwnerWaitingService service;
    @Autowired WaitingService waitingService;
    @Autowired WaitingQueryService queryService;
    @Autowired WaitingDeferHistoryRepository deferHistoryRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ApplicationEvents events;

    private String suffix;
    private Long ownerId;
    private Long storeId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString();
        ownerId = insertMember("owner", "OWNER");
        storeId = jdbc.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, ?, 'KOREAN', 'Seoul', true, 90, 30, 10)
                RETURNING id
                """, Long.class, ownerId, "defer-" + suffix);
        WaitingBusinessHourFixture.openNow(jdbc, clock, storeId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from waiting_defer_history where waiting_id in (select id from waiting where store_id = ?)",
                storeId);
        jdbc.update("delete from waiting where store_id = ?", storeId);
        WaitingBusinessHourFixture.clear(jdbc, storeId);
        jdbc.update("delete from store where id = ?", storeId);
        jdbc.update("delete from member where email like ?", "%-" + suffix + "@example.com");
    }

    @Test
    @DisplayName("연기하면 바로 뒤 진행 중 팀과 순번만 바뀌고 나머지 순번은 그대로다")
    void deferSwapsWithNextActiveTeamOnly() {
        Long first = insertWaiting(1, "WAITING");
        insertWaiting(2, "CANCELED");
        Long second = insertWaiting(3, "WAITING");
        Long third = insertWaiting(4, "CALLED");

        WaitingDeferResponse response = service.deferWaiting(ownerId, first);

        assertThat(response).isEqualTo(new WaitingDeferResponse(first, 1, 1, 1, 2));
        assertThat(queue()).containsExactly(second, first, third);
        assertThat(position(second)).isEqualTo(1);
        assertThat(position(first)).isEqualTo(2);
        assertThat(position(third)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select wait_number from waiting where id = ?", Integer.class, first))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("연기마다 변경 전후 순번과 처리자·시각을 이력에 남긴다")
    void deferRecordsHistory() {
        Long first = insertWaiting(1, "WAITING");
        insertWaiting(2, "WAITING");
        insertWaiting(3, "WAITING");

        service.deferWaiting(ownerId, first);
        service.deferWaiting(ownerId, first);

        assertThat(deferHistoryRepository.findAllByWaitingIdOrderByIdAsc(first))
                .extracting("beforePosition", "afterPosition", "actorId")
                .containsExactly(tuple(1, 2, ownerId),
                        tuple(2, 3, ownerId));
        assertThat(deferHistoryRepository.findAllByWaitingIdOrderByIdAsc(first))
                .allSatisfy(history -> assertThat(history.getCreatedAt().toInstant())
                        .isEqualTo(OffsetDateTime.now(clock).toInstant()));
        assertThat(jdbc.queryForObject("select defer_count from waiting where id = ?", Integer.class, first))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("뒤에 진행 중인 팀이 없으면 409이고 아무것도 바뀌지 않는다")
    void lastTeamCannotDefer() {
        Long first = insertWaiting(1, "WAITING");
        Long last = insertWaiting(2, "WAITING");
        insertWaiting(3, "SEATED");

        assertFailure(() -> service.deferWaiting(ownerId, last), ErrorCode.WAITING_NOT_DEFERRABLE);

        assertThat(queue()).containsExactly(first, last);
        assertThat(deferHistoryRepository.findAllByWaitingIdOrderByIdAsc(last)).isEmpty();
        assertThat(events.stream(WaitingQueueChangedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("사이에 호출된 팀이 있으면 건너뛰고 뒤 대기 팀과만 맞바꾼다")
    void deferSkipsCalledTeam() {
        Long me = insertWaiting(1, "WAITING");
        Long called = insertWaiting(2, "CALLED");
        Long next = insertWaiting(3, "WAITING");

        WaitingDeferResponse response = service.deferWaiting(ownerId, me);

        assertThat(response).isEqualTo(new WaitingDeferResponse(me, 1, 1, 1, 3));
        assertThat(queue()).containsExactly(next, called, me);
        assertThat(position(me)).isEqualTo(3);
        assertThat(deferHistoryRepository.findAllByWaitingIdOrderByIdAsc(me))
                .extracting("beforePosition", "afterPosition")
                .containsExactly(tuple(1, 3));
    }

    @Test
    @DisplayName("사이에 도착 확인한 팀이 있어도 건너뛰고 뒤 대기 팀과만 맞바꾼다")
    void deferSkipsConfirmedTeam() {
        Long me = insertWaiting(1, "WAITING");
        Long confirmed = insertWaiting(2, "CONFIRMED");
        Long next = insertWaiting(3, "WAITING");

        WaitingDeferResponse response = service.deferWaiting(ownerId, me);

        assertThat(response).isEqualTo(new WaitingDeferResponse(me, 1, 1, 1, 3));
        assertThat(queue()).containsExactly(next, confirmed, me);
    }

    @Test
    @DisplayName("뒤에 호출된 팀만 있으면 409이고 아무것도 바뀌지 않는다")
    void onlyCalledTeamBehindCannotDefer() {
        Long me = insertWaiting(1, "WAITING");
        Long called = insertWaiting(2, "CALLED");

        assertFailure(() -> service.deferWaiting(ownerId, me), ErrorCode.WAITING_NOT_DEFERRABLE);

        assertThat(queue()).containsExactly(me, called);
        assertThat(jdbc.queryForObject("select defer_count from waiting where id = ?", Integer.class, me))
                .isZero();
        assertThat(deferHistoryRepository.findAllByWaitingIdOrderByIdAsc(me)).isEmpty();
    }

    @Test
    @DisplayName("3번째 연기는 409이다")
    void thirdDeferIsRejected() {
        Long first = insertWaiting(1, "WAITING");
        insertWaiting(2, "WAITING");
        insertWaiting(3, "WAITING");
        insertWaiting(4, "WAITING");
        service.deferWaiting(ownerId, first);
        service.deferWaiting(ownerId, first);

        assertFailure(() -> service.deferWaiting(ownerId, first), ErrorCode.WAITING_DEFER_LIMIT_EXCEEDED);

        assertThat(position(first)).isEqualTo(3);
        assertThat(deferHistoryRepository.findAllByWaitingIdOrderByIdAsc(first)).hasSize(2);
    }

    @Test
    @DisplayName("연기하면 대기열 변경과 점주 목록 변경 이벤트를 발행한다")
    void deferPublishesQueueAndOwnerEvents() {
        Long first = insertWaiting(1, "WAITING");
        insertWaiting(2, "WAITING");

        service.deferWaiting(ownerId, first);

        assertThat(events.stream(WaitingQueueChangedEvent.class))
                .containsExactly(new WaitingQueueChangedEvent(storeId, TODAY));
        assertThat(events.stream(CustomerNotificationEvent.class))
                .containsExactly(new CustomerNotificationEvent(ownerId, NotificationType.WAITING_CHANGED, storeId,
                        "대기 목록이 변경되었습니다."));
    }

    @Test
    @DisplayName("연기 후 새로 신청한 팀은 맨 뒤에 선다")
    void newTeamAfterDeferJoinsAtEnd() {
        Long first = insertWaiting(1, "WAITING");
        Long second = insertWaiting(2, "WAITING");
        service.deferWaiting(ownerId, first);
        Long newcomer = insertMember("newcomer", "CUSTOMER");

        var created = waitingService.createWaiting(newcomer, storeId, 2).response();

        assertThat(created.waitNumber()).isEqualTo(3);
        assertThat(created.teamsAhead()).isEqualTo(2);
        assertThat(queue()).containsExactly(second, first, created.waitingId());
    }

    private List<Long> queue() {
        return service.getWaitings(ownerId, storeId).stream().map(OwnerWaitingResponse::id).toList();
    }

    private Integer position(Long waitingId) {
        Long memberId = jdbc.queryForObject("select member_id from waiting where id = ?", Long.class, waitingId);
        return queryService.getMyWaiting(memberId, waitingId).currentPosition();
    }

    private Long insertWaiting(int number, String status) {
        Long customerId = insertMember("customer" + number, "CUSTOMER");
        return jdbc.queryForObject("""
                INSERT INTO waiting (
                    member_id, store_id, waiting_date, party_count, wait_number, queue_order, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, 2, ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, customerId, storeId, TODAY, number, number, status);
    }

    private Long insertMember(String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'test', '010-0000-0000', ?)
                RETURNING id
                """, Long.class, name + "-" + suffix + "@example.com", type);
    }

    private static void assertFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
