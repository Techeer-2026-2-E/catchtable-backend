package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.entity.ArrivalIntent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 웨이팅 신청 서비스 테스트
// @Transactional 없음 → 동시성 테스트에서 스레드 간 커밋 데이터 공유 필요, 직접 정리
@SpringBootTest
class WaitingServiceTest {

    @Autowired WaitingService waitingService;
    @Autowired WaitingRepository waitingRepository;
    @Autowired WaitingEndHistoryRepository historyRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final List<Member> createdMembers = new ArrayList<>(); // 정리 대상 회원
    private Store store;                                            // 테스트 매장

    // 매장 생성 + 웨이팅 접수 오픈
    @BeforeEach
    void setUp() {
        Member owner = saveMember("점주");
        Store newStore = Store.builder()
                .owner(owner)
                .name("테스트 매장")
                .category(StoreCategory.values()[0])
                .address("서울시 테스트구")
                .reservationDurationMinutes(90)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();
        newStore.openWaiting();
        store = storeRepository.save(newStore);
    }

    // 데이터 정리 (웨이팅 → 매장 → 회원 순, FK 때문)
    @AfterEach
    void tearDown() {
        historyRepository.deleteAllInBatch();
        waitingRepository.deleteAllInBatch();
        storeRepository.delete(store);
        memberRepository.deleteAll(createdMembers);
        createdMembers.clear();
    }

    // 정상 신청: 번호 1, 2 순서 발급
    @Test
    @DisplayName("웨이팅을 신청하면 대기번호가 순서대로 발급된다")
    void create_issuesSequentialNumber() {
        Member a = saveMember("고객A");
        Member b = saveMember("고객B");

        WaitingCreateResult first = waitingService.createWaiting(a.getId(), store.getId(), 2);
        WaitingCreateResult second = waitingService.createWaiting(b.getId(), store.getId(), 3);

        assertThat(first.created()).isTrue();
        assertThat(first.response().waitNumber()).isEqualTo(1);
        assertThat(first.response().teamsAhead()).isZero();
        assertThat(second.response().waitNumber()).isEqualTo(2);
        assertThat(second.response().teamsAhead()).isEqualTo(1);
    }

    // 접수 마감 매장 → WAITING_CLOSED
    @Test
    @DisplayName("접수 마감된 매장에는 신청할 수 없다")
    void create_closedStore_fails() {
        closeWaiting();
        Member customer = saveMember("고객");

        assertThatThrownBy(() -> waitingService.createWaiting(customer.getId(), store.getId(), 2))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_CLOSED);
    }

    // 없는 매장 → STORE_NOT_FOUND
    @Test
    @DisplayName("존재하지 않는 매장에는 신청할 수 없다")
    void create_storeNotFound_fails() {
        Member customer = saveMember("고객");

        assertThatThrownBy(() -> waitingService.createWaiting(customer.getId(), -1L, 2))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STORE_NOT_FOUND);
    }

    // 같은 인원 재시도 → 기존 반환, 1건만 저장
    @Test
    @DisplayName("같은 인원으로 재시도하면 새로 등록하지 않고 기존 웨이팅을 돌려준다")
    void create_retrySamePartyCount_returnsExisting() {
        Member customer = saveMember("고객");

        WaitingCreateResult first = waitingService.createWaiting(customer.getId(), store.getId(), 2);
        WaitingCreateResult retry = waitingService.createWaiting(customer.getId(), store.getId(), 2);

        assertThat(retry.created()).isFalse();
        assertThat(retry.response().waitingId()).isEqualTo(first.response().waitingId());
        assertThat(waitingRepository.count()).isEqualTo(1);
    }

    // 다른 인원 재신청 → DUPLICATE_ACTIVE_WAITING
    @Test
    @DisplayName("진행 중인 웨이팅이 있는데 다른 인원으로 신청하면 실패한다")
    void create_differentPartyCount_fails() {
        Member customer = saveMember("고객");
        waitingService.createWaiting(customer.getId(), store.getId(), 2);

        assertThatThrownBy(() -> waitingService.createWaiting(customer.getId(), store.getId(), 4))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_ACTIVE_WAITING);
    }

    // 신청 → 마감 → 재시도: 마감 에러 대신 기존 반환
    @Test
    @DisplayName("신청 후 접수가 마감되어도 재시도는 기존 웨이팅을 돌려준다")
    void create_retryAfterClose_returnsExisting() {
        Member customer = saveMember("고객");
        WaitingCreateResult first = waitingService.createWaiting(customer.getId(), store.getId(), 2);

        closeWaiting();
        WaitingCreateResult retry = waitingService.createWaiting(customer.getId(), store.getId(), 2);

        assertThat(retry.created()).isFalse();
        assertThat(retry.response().waitingId()).isEqualTo(first.response().waitingId());
    }

    // 동시성: 같은 고객 10번 동시 요청 → 1건
    @Test
    @DisplayName("같은 고객이 동시에 여러 번 신청해도 한 번만 등록된다")
    void create_concurrentSameCustomer_registersOnce() throws InterruptedException {
        Member customer = saveMember("고객");
        Set<Long> waitingIds = ConcurrentHashMap.newKeySet();

        runConcurrently(10, i -> waitingIds.add(
                waitingService.createWaiting(customer.getId(), store.getId(), 2).response().waitingId()));

        assertThat(waitingRepository.count()).isEqualTo(1);
        assertThat(waitingIds).hasSize(1);
    }

    // 동시성: 고객 10명 동시 요청 → 번호 1~10 중복 없음
    @Test
    @DisplayName("여러 고객이 동시에 신청해도 대기번호가 중복 없이 발급된다")
    void create_concurrentCustomers_uniqueNumbers() throws InterruptedException {
        int count = 10;
        List<Member> customers = IntStream.range(0, count)
                .mapToObj(i -> saveMember("고객" + i))
                .toList();
        Set<Integer> numbers = ConcurrentHashMap.newKeySet();

        runConcurrently(count, i -> numbers.add(
                waitingService.createWaiting(customers.get(i).getId(), store.getId(), 2).response().waitNumber()));

        Set<Integer> expected = IntStream.rangeClosed(1, count).boxed().collect(Collectors.toSet());
        assertThat(numbers).isEqualTo(expected);
    }

    // 응답: CALLED 유지, 만료 연장 없음
    @Test
    @DisplayName("호출된 웨이팅에 응답하면 상태는 CALLED 그대로 두고 응답 시각과 방문 의사를 기록한다")
    void respond_calledWaiting_recordsIntentWithoutExtendingExpiry() {
        Member customer = saveMember("고객");
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusMinutes(10).withNano(0);
        Waiting waiting = saveCalledWaiting(customer, WaitingStatus.CALLED, expiresAt);

        var response = waitingService.respondToCall(customer.getId(), waiting.getId());

        assertThat(response.waitingId()).isEqualTo(waiting.getId());
        assertThat(response.status()).isEqualTo(WaitingStatus.CALLED);
        assertThat(response.arrivalIntent()).isEqualTo(ArrivalIntent.COMING);
        assertThat(response.respondedAt()).isNotNull();
        assertThat(response.expiresAt().toInstant()).isEqualTo(expiresAt.toInstant());
        Waiting saved = waitingRepository.findById(waiting.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(WaitingStatus.CALLED);
        assertThat(saved.getArrivalIntent()).isEqualTo(ArrivalIntent.COMING);
        assertThat(saved.getRespondedAt()).isNotNull();
        assertThat(saved.getCallExpiresAt().toInstant()).isEqualTo(expiresAt.toInstant());
    }

    // 반복 응답 멱등
    @Test
    @DisplayName("같은 응답을 반복하면 성공하고 처음 응답 시각을 유지한다")
    void respond_retry_keepsFirstRespondedAt() {
        Member customer = saveMember("고객");
        Waiting waiting = saveCalledWaiting(customer, WaitingStatus.CALLED, OffsetDateTime.now(clock).plusMinutes(10));
        waitingService.respondToCall(customer.getId(), waiting.getId());
        OffsetDateTime first = waitingRepository.findById(waiting.getId()).orElseThrow().getRespondedAt();

        var retried = waitingService.respondToCall(customer.getId(), waiting.getId());

        assertThat(retried.arrivalIntent()).isEqualTo(ArrivalIntent.COMING);
        assertThat(retried.respondedAt().toInstant()).isEqualTo(first.toInstant());
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getRespondedAt().toInstant())
                .isEqualTo(first.toInstant());
    }

    // 호출 상태가 아니면 409
    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"WAITING", "SEATED", "CANCELED", "NO_SHOW"})
    @DisplayName("호출 상태가 아닌 웨이팅은 응답할 수 없다")
    void respond_notCalled_fails(WaitingStatus status) {
        Member customer = saveMember("고객");
        Waiting waiting = saveCalledWaiting(customer, status, OffsetDateTime.now(clock).plusMinutes(10));

        assertThatThrownBy(() -> waitingService.respondToCall(customer.getId(), waiting.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_RESPONDABLE);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getRespondedAt()).isNull();
    }

    // 만료 후 409
    @Test
    @DisplayName("호출 만료 시각이 지나면 응답할 수 없다")
    void respond_afterExpiry_fails() {
        Member customer = saveMember("고객");
        Waiting waiting = saveCalledWaiting(customer, WaitingStatus.CALLED, OffsetDateTime.now(clock).minusMinutes(1));

        assertThatThrownBy(() -> waitingService.respondToCall(customer.getId(), waiting.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_RESPONDABLE);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getRespondedAt()).isNull();
    }

    // 남의·없는 웨이팅 404
    @Test
    @DisplayName("다른 고객의 웨이팅이나 없는 웨이팅은 404")
    void respond_othersOrUnknown_notFound() {
        Member customer = saveMember("고객");
        Member other = saveMember("다른 고객");
        Waiting waiting = saveCalledWaiting(customer, WaitingStatus.CALLED, OffsetDateTime.now(clock).plusMinutes(10));

        assertThatThrownBy(() -> waitingService.respondToCall(other.getId(), waiting.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_FOUND);
        assertThatThrownBy(() -> waitingService.respondToCall(customer.getId(), -1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_FOUND);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getRespondedAt()).isNull();
    }

    // 고객 취소: CANCELED, 종료 이력 저장
    @Test
    @DisplayName("대기 중인 웨이팅을 취소하면 CANCELED가 되고 고객 취소 이력이 남는다")
    void cancel_waiting_recordsCustomerHistory() {
        Member member = saveMember("고객");
        Long waitingId = waitingService.createWaiting(member.getId(), store.getId(), 2).response().waitingId();

        var response = waitingService.cancelWaiting(member.getId(), waitingId);

        assertThat(response.waitingId()).isEqualTo(waitingId);
        assertThat(response.status()).isEqualTo(WaitingStatus.CANCELED);
        assertThat(response.reason()).isEqualTo(WaitingEndReason.CUSTOMER_CANCELED);
        assertThat(response.detail()).isEqualTo("고객 취소");
        assertThat(response.endedAt()).isNotNull();
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waitingId)).singleElement().satisfies(history -> {
            assertThat(history.getPreviousStatus()).isEqualTo(WaitingStatus.WAITING);
            assertThat(history.getReason()).isEqualTo(WaitingEndReason.CUSTOMER_CANCELED);
            assertThat(history.getDetail()).isEqualTo("고객 취소");
            assertThat(history.getActorId()).isEqualTo(member.getId());
        });
    }

    // 호출 중·응답 후 취소
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("호출된 웨이팅은 방문 의사 응답 여부와 상관없이 취소할 수 있다")
    void cancel_calledWaiting_succeeds(boolean responded) {
        Member member = saveMember("고객");
        Waiting waiting = saveCalledWaiting(member, WaitingStatus.CALLED, OffsetDateTime.now(clock).plusMinutes(10));
        if (responded) {
            waitingService.respondToCall(member.getId(), waiting.getId());
        }

        assertThat(waitingService.cancelWaiting(member.getId(), waiting.getId()).status()).isEqualTo(WaitingStatus.CANCELED);
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).singleElement()
                .satisfies(history -> assertThat(history.getPreviousStatus()).isEqualTo(WaitingStatus.CALLED));
    }

    // 이미 취소면 그대로
    @Test
    @DisplayName("같은 취소를 반복하면 성공하고 이력은 한 번만 남는다")
    void cancel_retry_keepsSingleHistory() {
        Member member = saveMember("고객");
        Long waitingId = waitingService.createWaiting(member.getId(), store.getId(), 2).response().waitingId();
        waitingService.cancelWaiting(member.getId(), waitingId);
        OffsetDateTime endedAt = waitingRepository.findById(waitingId).orElseThrow().getEndedAt();

        var retried = waitingService.cancelWaiting(member.getId(), waitingId);

        assertThat(retried.status()).isEqualTo(WaitingStatus.CANCELED);
        assertThat(retried.endedAt().toInstant()).isEqualTo(endedAt.toInstant());
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waitingId)).hasSize(1);
    }

    // 점주 취소 건은 현재 상태만
    @Test
    @DisplayName("점주가 이미 취소한 웨이팅은 현재 상태를 그대로 돌려준다")
    void cancel_ownerCanceled_returnsCurrentState() {
        Member member = saveMember("고객");
        Waiting waiting = waitingRepository.save(Waiting.create(member, store, LocalDate.now(clock), 2, 1));
        jdbc.update("update waiting set status = 'CANCELED', end_reason = 'OWNER_CANCELED', end_detail = '매장 사정', "
                + "ended_at = now() where id = ?", waiting.getId());

        var response = waitingService.cancelWaiting(member.getId(), waiting.getId());

        assertThat(response.status()).isEqualTo(WaitingStatus.CANCELED);
        assertThat(response.reason()).isEqualTo(WaitingEndReason.OWNER_CANCELED);
        assertThat(response.detail()).isEqualTo("매장 사정");
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).isEmpty();
    }

    // 입장·노쇼 409
    @ParameterizedTest
    @EnumSource(value = WaitingStatus.class, names = {"SEATED", "NO_SHOW"})
    @DisplayName("입장했거나 노쇼 처리된 웨이팅은 취소할 수 없다")
    void cancel_endedWaiting_fails(WaitingStatus status) {
        Member member = saveMember("고객");
        Waiting waiting = waitingRepository.save(Waiting.create(member, store, LocalDate.now(clock), 2, 1));
        jdbc.update("update waiting set status = ? where id = ?", status.name(), waiting.getId());

        assertThatThrownBy(() -> waitingService.cancelWaiting(member.getId(), waiting.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_CANCELLABLE);
        assertThat(waitingRepository.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(historyRepository.findAllByWaitingIdOrderByIdAsc(waiting.getId())).isEmpty();
    }

    // 취소 후 재신청
    @Test
    @DisplayName("취소한 뒤 같은 매장에 다시 신청하면 새 번호로 등록된다")
    void cancel_thenApplyAgain_createsNewWaiting() {
        Member member = saveMember("고객");
        Long canceledId = waitingService.createWaiting(member.getId(), store.getId(), 2).response().waitingId();
        waitingService.cancelWaiting(member.getId(), canceledId);

        WaitingCreateResult again = waitingService.createWaiting(member.getId(), store.getId(), 2);

        assertThat(again.created()).isTrue();
        assertThat(again.response().waitingId()).isNotEqualTo(canceledId);
        assertThat(again.response().waitNumber()).isEqualTo(2);
    }

    // 남의·없는 웨이팅 404
    @Test
    @DisplayName("다른 고객의 웨이팅이나 없는 웨이팅 취소는 404")
    void cancel_othersOrUnknown_notFound() {
        Member owner = saveMember("주인");
        Member other = saveMember("남");
        Long waitingId = waitingService.createWaiting(owner.getId(), store.getId(), 2).response().waitingId();

        assertThatThrownBy(() -> waitingService.cancelWaiting(other.getId(), waitingId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_FOUND);
        assertThatThrownBy(() -> waitingService.cancelWaiting(owner.getId(), -1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WAITING_NOT_FOUND);
        assertThat(waitingRepository.findById(waitingId).orElseThrow().getStatus()).isEqualTo(WaitingStatus.WAITING);
    }

    // 호출된 웨이팅 저장
    private Waiting saveCalledWaiting(Member member, WaitingStatus status, OffsetDateTime expiresAt) {
        Waiting waiting = waitingRepository.save(Waiting.create(member, store, LocalDate.now(clock), 2, 1));
        jdbc.update("update waiting set status = ?, called_at = ?, call_expires_at = ?, call_count = 1 where id = ?",
                status.name(), expiresAt.minusMinutes(10), expiresAt, waiting.getId());
        return waiting;
    }

    // 회원 저장 헬퍼
    private Member saveMember(String name) {
        Member member = memberRepository.save(Member.builder()
                .name(name)
                .phone("010-0000-0000")
                .userType(UserType.values()[0]) // TODO: 실제 enum 값으로 변경 (예: UserType.CUSTOMER)
                .build());
        createdMembers.add(member);
        return member;
    }

    // 접수 마감 헬퍼
    private void closeWaiting() {
        Store found = storeRepository.findById(store.getId()).orElseThrow();
        found.closeWaiting();
        storeRepository.save(found);
    }

    // 동시 실행 헬퍼: 전 스레드 준비 → 동시 출발 → 전부 끝날 때까지 대기
    private void runConcurrently(int threadCount, IntConsumer task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount); // 준비 완료 신호
        CountDownLatch start = new CountDownLatch(1);           // 출발 신호
        CountDownLatch done = new CountDownLatch(threadCount);  // 종료 신호

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.accept(index);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();       // 전원 준비 대기
        start.countDown();   // 동시 출발
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
    }
}