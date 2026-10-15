package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class OwnerWaitingServiceTest {

    @Autowired OwnerWaitingService service;
    @Autowired WaitingQueryService queryService;
    @Autowired WaitingRepository waitingRepository;
    @Autowired WaitingEndHistoryRepository historyRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired SseEmitterRepository emitterRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired Clock clock;

    private final List<Member> members = new ArrayList<>();
    private Member owner;
    private Member customer;
    private Store store;
    private SseEmitter ownerEmitter;
    private SseEmitter customerEmitter;

    @BeforeEach
    void setUp() {
        owner = member("점주", UserType.OWNER);
        customer = member("고객", UserType.CUSTOMER);
        store = storeRepository.save(Store.builder().owner(owner).name("테스트 매장")
                .category(StoreCategory.values()[0]).address("서울시")
                .reservationDurationMinutes(90).reservationSlotMinutes(30).arrivalGraceMinutes(10).build());
        ownerEmitter = mock(SseEmitter.class);
        customerEmitter = mock(SseEmitter.class);
        emitterRepository.save(owner.getId(), ownerEmitter);
        emitterRepository.save(customer.getId(), customerEmitter);
    }

    @AfterEach
    void cleanUp() {
        emitterRepository.delete(owner.getId(), ownerEmitter);
        emitterRepository.delete(customer.getId(), customerEmitter);
        historyRepository.deleteAllInBatch();
        waitingRepository.deleteAllInBatch();
        storeRepository.delete(store);
        memberRepository.deleteAll(members);
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"WAITING", "CALLED", "CONFIRMED"})
    void cancelRemovesTeamAndKeepsNextTeamWaiting(WaitingStatus status) throws Exception {
        Waiting first = waiting(1, status);
        Member nextCustomer = member("다음 고객", UserType.CUSTOMER);
        Waiting next = waitingRepository.save(
                Waiting.create(nextCustomer, store, LocalDate.now(clock), 2, 2));

        service.cancelWaiting(owner.getId(), first.getId(), "  매장 사정  ");
        service.cancelWaiting(owner.getId(), first.getId(), "다른 사유로 재시도");

        Waiting canceled = waitingRepository.findById(first.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(WaitingStatus.CANCELED);
        assertThat(canceled.getEndDetail()).isEqualTo("매장 사정");
        assertThat(canceled.getEndReason()).isEqualTo(WaitingEndReason.OWNER_CANCELED);
        assertThat(canceled.getEndedAt()).isNotNull();
        assertThat(queryService.getMyWaiting(nextCustomer.getId(), next.getId()).currentPosition()).isEqualTo(1);
        assertThat(waitingRepository.findById(next.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.WAITING);
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(first.getId())).singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(status);
                    assertThat(history.getActorId()).isEqualTo(owner.getId());
                });
        verifyOneTerminationNotification();
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"WAITING", "CALLED"})
    void terminationNotifiesRemainingCustomerOnceOnRetry(WaitingStatus status) throws Exception {
        Waiting first = waiting(1, status);
        Member remainingCustomer = member("남은 고객", UserType.CUSTOMER);
        Waiting next = waitingRepository.save(
                Waiting.create(remainingCustomer, store, LocalDate.now(clock), 2, 2));
        SseEmitter remainingEmitter = mock(SseEmitter.class);
        emitterRepository.save(remainingCustomer.getId(), remainingEmitter);
        try {
            assertThat(queryService.getMyWaiting(remainingCustomer.getId(), next.getId()).currentPosition())
                    .isEqualTo(2);

            if (status == WaitingStatus.WAITING) {
                service.cancelWaiting(owner.getId(), first.getId(), "매장 사정");
                service.cancelWaiting(owner.getId(), first.getId(), "다른 사유로 재시도");
            } else {
                service.markNoShow(owner.getId(), first.getId(), WaitingEndReason.NO_RESPONSE, "응답 없음");
                service.markNoShow(owner.getId(), first.getId(), WaitingEndReason.NO_RESPONSE, "응답 없음");
                service.markNoShow(owner.getId(), first.getId(), WaitingEndReason.NO_SHOW, "미방문 확인");
            }

            assertThat(queryService.getMyWaiting(remainingCustomer.getId(), next.getId()).currentPosition())
                    .isEqualTo(1);
            verify(remainingEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verifyOneTerminationNotification();
        } finally {
            emitterRepository.delete(remainingCustomer.getId(), remainingEmitter);
        }
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"CALLED", "CONFIRMED"})
    void noShowRetryAndCorrectionPreserveFirstTermination(WaitingStatus status) throws Exception {
        Waiting waiting = waiting(1, status);
        service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_RESPONSE, "응답 없음");
        var ended = waitingRepository.findById(waiting.getId()).orElseThrow();
        service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_RESPONSE, " 응답 없음 ");
        var corrected = service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_SHOW, "응답 후 미방문");

        assertThat(corrected.status()).isEqualTo(WaitingStatus.NO_SHOW);
        assertThat(corrected.endedAt()).isEqualTo(ended.getEndedAt());
        var history = historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getPreviousStatus()).isEqualTo(WaitingStatus.NO_SHOW);
        assertThat(history.get(1).getPreviousReason()).isEqualTo(WaitingEndReason.NO_RESPONSE);
        assertThat(history.get(1).getPreviousDetail()).isEqualTo("응답 없음");
        assertThat(history.get(1).getReason()).isEqualTo(WaitingEndReason.NO_SHOW);
        assertThat(history.get(1).getDetail()).isEqualTo("응답 후 미방문");
        assertThat(history.get(1).getActorId()).isEqualTo(owner.getId());
        verifyOneTerminationNotification();
    }

    @Test
    void alreadyExpiredWaitingOnlyCorrectsReasonWithoutSendingAgain() throws Exception {
        Waiting waiting = waiting(1, WaitingStatus.CALLED);
        OffsetDateTime expiredAt = OffsetDateTime.now(clock).minusMinutes(1).withNano(0);
        autoExpire(waiting.getId(), expiredAt);

        var corrected = service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_SHOW, "미방문 확인");

        assertThat(corrected.endedAt().toInstant()).isEqualTo(expiredAt.toInstant());
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(WaitingStatus.NO_SHOW);
                    assertThat(history.getPreviousReason()).isEqualTo(WaitingEndReason.AUTO_EXPIRED);
                });
        verify(ownerEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(customerEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"SEATED", "NO_SHOW"})
    void cannotCancelEndedTeam(WaitingStatus status) {
        Waiting waiting = waiting(1, status);
        assertBusinessFailure(() -> service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정"),
                ErrorCode.WAITING_NOT_CANCELLABLE);
        assertThat(historyRepository.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"WAITING", "SEATED", "CANCELED"})
    void cannotMarkUncalledOrOtherwiseEndedTeamNoShow(WaitingStatus status) {
        Waiting waiting = waiting(1, status);
        assertBusinessFailure(() -> service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_SHOW, "미방문"),
                ErrorCode.WAITING_NOT_TERMINABLE);
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void mutationChecksOwnerRoleAndStoreOwnership() {
        Waiting waiting = waiting(1, WaitingStatus.CALLED);
        Member other = member("다른 점주", UserType.OWNER);
        assertBusinessFailure(() -> service.cancelWaiting(customer.getId(), waiting.getId(), "사유"),
                ErrorCode.FORBIDDEN_MEMBER_TYPE);
        assertBusinessFailure(() -> service.cancelWaiting(other.getId(), waiting.getId(), "사유"),
                ErrorCode.WAITING_NOT_FOUND);
        assertBusinessFailure(() -> service.markNoShow(other.getId(), waiting.getId(), WaitingEndReason.NO_SHOW, "미방문"),
                ErrorCode.WAITING_NOT_FOUND);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.CALLED);
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void rollbackRestoresStatusAndHistoryAndSendsNothing() throws Exception {
        Waiting waiting = waiting(1, WaitingStatus.WAITING);
        transactions.executeWithoutResult(transaction -> {
            service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정");
            transaction.setRollbackOnly();
        });
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.WAITING);
        assertThat(historyRepository.count()).isZero();
        verify(ownerEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(customerEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void simultaneousCancelRetriesTerminateOnlyOnce() throws Exception {
        Waiting waiting = waiting(1, WaitingStatus.WAITING);
        race(() -> { service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정"); return 1; },
                () -> { service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정"); return 1; });
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).hasSize(1);
        verifyOneTerminationNotification();
    }

    @Test
    void simultaneousCancelAndNoShowKeepTheFirstFinalState() throws Exception {
        Waiting waiting = waiting(1, WaitingStatus.CALLED);
        var results = race(
                () -> outcome(() -> service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정")),
                () -> outcome(() -> service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_SHOW, "미방문")));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getStatus())
                .isIn(WaitingStatus.CANCELED, WaitingStatus.NO_SHOW);
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).hasSize(1);
        verifyOneTerminationNotification();
    }

    @Test
    void automaticExpiryAndManualNoShowDoNotOverwriteTheFirstTerminationTime() throws Exception {
        Waiting waiting = waiting(1, WaitingStatus.CALLED);
        OffsetDateTime expiryTime = OffsetDateTime.now(clock).minusMinutes(1).withNano(0);
        var results = race(() -> autoExpire(waiting.getId(), expiryTime), () -> {
            service.markNoShow(owner.getId(), waiting.getId(), WaitingEndReason.NO_RESPONSE, "미응답 확인");
            return 0;
        });

        Waiting ended = waitingRepository.findById(waiting.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(WaitingStatus.NO_SHOW);
        assertThat(ended.getEndReason()).isEqualTo(WaitingEndReason.NO_RESPONSE);
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).hasSize(1);
        if (results.getFirst() == 1) {
            assertThat(ended.getEndedAt().toInstant()).isEqualTo(expiryTime.toInstant());
            verify(customerEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        } else {
            assertThat(ended.getEndedAt().toInstant()).isAfter(expiryTime.toInstant());
            verifyOneTerminationNotification();
        }
    }

    private int autoExpire(Long waitingId, OffsetDateTime time) {
        return jdbc.update("""
                update waiting set status = 'NO_SHOW', end_reason = 'AUTO_EXPIRED',
                    end_detail = '호출 시간 만료', ended_at = ?, version = version + 1
                where id = ? and status in ('CALLED', 'CONFIRMED')
                """, time, waitingId);
    }

    @Test
    void aStaleJpaWriterCannotOverwriteAnOwnerCancellation() throws Exception {
        Waiting waiting = waiting(1, WaitingStatus.CALLED);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> transactions.executeWithoutResult(transaction -> {
                Waiting previous = waitingRepository.findById(waiting.getId()).orElseThrow();
                loaded.countDown();
                try {
                    assertThat(canceled.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                previous.markNoShow(WaitingEndReason.NO_RESPONSE, "늦은 처리", owner.getId(), OffsetDateTime.now(clock));
            }));
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                service.cancelWaiting(owner.getId(), waiting.getId(), "매장 사정");
            } finally {
                canceled.countDown();
            }
            assertThatThrownBy(() -> stale.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(WaitingStatus.CANCELED);
        verifyOneTerminationNotification();
    }

    private boolean outcome(Runnable action) {
        try {
            action.run();
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode()).isIn(ErrorCode.WAITING_NOT_CANCELLABLE, ErrorCode.WAITING_NOT_TERMINABLE);
            return false;
        }
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private void assertBusinessFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void verifyOneTerminationNotification() throws Exception {
        verify(ownerEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(customerEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    private Waiting waiting(int number, WaitingStatus status) {
        Waiting saved = waitingRepository.save(Waiting.create(customer, store, LocalDate.now(clock), 2, number));
        jdbc.update("update waiting set status = ? where id = ?", status.name(), saved.getId());
        return saved;
    }

    private Member member(String name, UserType type) {
        Member saved = memberRepository.save(Member.builder().name(name).phone("010-0000-0000")
                .userType(type).build());
        members.add(saved);
        return saved;
    }
}
