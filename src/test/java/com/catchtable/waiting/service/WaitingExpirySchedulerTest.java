package com.catchtable.waiting.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 스케줄 비활성화, 실시간 스케줄과 겹치지 않는 미래 고정 시각
@SpringBootTest(properties = "waiting.expiry-interval-ms=3600000")
@RecordApplicationEvents
class WaitingExpirySchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 11);

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2030-01-10T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired WaitingExpiryScheduler scheduler;
    @Autowired OwnerWaitingService ownerWaitingService;
    @Autowired WaitingRepository waitingRepository;
    @Autowired WaitingEndHistoryRepository historyRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired SseEmitterRepository emitterRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;
    @Autowired Clock clock;

    private final List<Store> stores = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();
    private final Map<Long, SseEmitter> emitters = new HashMap<>();
    private OffsetDateTime now;
    private Member owner;
    private Store store;

    @BeforeEach
    void setUp() {
        // 공용 DB에 남은 만료 대상 선처리
        scheduler.expireCalledWaitings();
        events.clear();
        now = OffsetDateTime.now(clock);
        owner = member("점주", UserType.OWNER);
        store = store("테스트 매장");
    }

    @AfterEach
    void cleanUp() {
        emitters.forEach(emitterRepository::delete);
        for (Store saved : stores) {
            jdbc.update("delete from waiting_end_history where waiting_id in (select id from waiting where store_id = ?)",
                    saved.getId());
            jdbc.update("delete from waiting where store_id = ?", saved.getId());
            storeRepository.delete(saved);
        }
        memberRepository.deleteAll(members);
    }

    @Test
    void expiresOnlyOverdueTeamsOnceAndNotifiesAfterCommit() throws Exception {
        Member expiredCustomer = member("만료 고객", UserType.CUSTOMER);
        Member calledCustomer = member("호출 고객", UserType.CUSTOMER);
        Member waitingCustomer = member("대기 고객", UserType.CUSTOMER);
        Waiting overdue = waiting(expiredCustomer, store, TODAY, 1, WaitingStatus.CALLED, now.minusSeconds(1));
        Waiting notYet = waiting(calledCustomer, store, TODAY, 2, WaitingStatus.CALLED, now);
        Waiting waiting = waiting(waitingCustomer, store, TODAY, 3, WaitingStatus.WAITING, null);

        assertThat(scheduler.expireCalledWaitings()).isEqualTo(1);
        assertThat(scheduler.expireCalledWaitings()).isZero();

        Waiting expired = waitingRepository.findById(overdue.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(WaitingStatus.NO_SHOW);
        assertThat(expired.getEndReason()).isEqualTo(WaitingEndReason.AUTO_EXPIRED);
        assertThat(expired.getEndedAt().toInstant()).isEqualTo(now.toInstant());
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(overdue.getId())).singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(WaitingStatus.CALLED);
                    assertThat(history.getReason()).isEqualTo(WaitingEndReason.AUTO_EXPIRED);
                    assertThat(history.getActorId()).isNull();
                });
        assertThat(waitingRepository.findById(notYet.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.CALLED);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.WAITING);

        assertThat(events.stream(CustomerNotificationEvent.class))
                .extracting(CustomerNotificationEvent::memberId, CustomerNotificationEvent::type,
                        CustomerNotificationEvent::targetId)
                .containsExactly(
                        tuple(expiredCustomer.getId(), NotificationType.WAITING_EXPIRED, overdue.getId()),
                        tuple(owner.getId(), NotificationType.WAITING_CHANGED, store.getId()));
        assertThat(events.stream(WaitingQueueChangedEvent.class))
                .containsExactly(new WaitingQueueChangedEvent(store.getId(), TODAY));
        // 만료 고객: 만료 알림 1건, 남은 고객: 순번 1건
        verify(emitters.get(expiredCustomer.getId()), times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitters.get(owner.getId()), times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitters.get(calledCustomer.getId()), times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitters.get(waitingCustomer.getId()), times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void recalledTeamWithExtendedDeadlineIsNotExpired() {
        Waiting overdue = waiting(member("고객", UserType.CUSTOMER), store, TODAY, 1,
                WaitingStatus.CALLED, now.minusMinutes(1));
        ownerWaitingService.callWaiting(owner.getId(), overdue.getId());
        events.clear();

        assertThat(scheduler.expireCalledWaitings()).isZero();

        Waiting recalled = waitingRepository.findById(overdue.getId()).orElseThrow();
        assertThat(recalled.getStatus()).isEqualTo(WaitingStatus.CALLED);
        assertThat(recalled.getCallExpiresAt()).isAfter(now);
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(overdue.getId())).isEmpty();
        assertThat(events.stream()).isEmpty();
    }

    @Test
    void otherStoresAndDatesAreNotAffected() throws Exception {
        Store otherStore = store("다른 매장");
        Member otherStoreCustomer = member("다른 매장 고객", UserType.CUSTOMER);
        Member otherDateCustomer = member("다른 날 고객", UserType.CUSTOMER);
        Waiting overdue = waiting(member("만료 고객", UserType.CUSTOMER), store, TODAY, 1,
                WaitingStatus.CALLED, now.minusMinutes(1));
        Waiting otherStoreCalled = waiting(otherStoreCustomer, otherStore, TODAY, 1,
                WaitingStatus.CALLED, now.plusMinutes(5));
        Waiting otherDateWaiting = waiting(otherDateCustomer, store, TODAY.plusDays(1), 1,
                WaitingStatus.WAITING, null);

        assertThat(scheduler.expireCalledWaitings()).isEqualTo(1);

        assertThat(waitingRepository.findById(overdue.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.NO_SHOW);
        assertThat(waitingRepository.findById(otherStoreCalled.getId()).orElseThrow().getStatus())
                .isEqualTo(WaitingStatus.CALLED);
        assertThat(waitingRepository.findById(otherDateWaiting.getId()).orElseThrow().getStatus())
                .isEqualTo(WaitingStatus.WAITING);
        assertThat(events.stream(WaitingQueueChangedEvent.class))
                .containsExactly(new WaitingQueueChangedEvent(store.getId(), TODAY));
        verify(emitters.get(otherStoreCustomer.getId()), never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitters.get(otherDateCustomer.getId()), never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private Waiting waiting(Member customer, Store target, LocalDate date, int number,
                            WaitingStatus status, OffsetDateTime callExpiresAt) {
        Waiting saved = waitingRepository.save(Waiting.create(customer, target, date, 2, number));
        jdbc.update("update waiting set status = ?, called_at = ?, call_expires_at = ? where id = ?",
                status.name(), callExpiresAt == null ? null : callExpiresAt.minusMinutes(10), callExpiresAt,
                saved.getId());
        return saved;
    }

    private Store store(String name) {
        Store saved = storeRepository.save(Store.builder().owner(owner).name(name)
                .category(StoreCategory.values()[0]).address("서울시")
                .reservationDurationMinutes(90).reservationSlotMinutes(30).arrivalGraceMinutes(10).build());
        stores.add(saved);
        return saved;
    }

    private Member member(String name, UserType type) {
        Member saved = memberRepository.save(Member.builder().name(name).phone("010-0000-0000")
                .userType(type).build());
        members.add(saved);
        SseEmitter emitter = mock(SseEmitter.class);
        emitterRepository.save(saved.getId(), emitter);
        emitters.put(saved.getId(), emitter);
        return saved;
    }
}
