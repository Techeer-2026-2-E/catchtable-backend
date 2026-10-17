package com.catchtable.waiting.controller;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.service.WaitingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(OwnerWaitingControllerTest.FixedTime.class)
class OwnerWaitingControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 11);

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2030-01-10T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired WaitingService waitingService;
    @Autowired WaitingRepository waitingRepository;
    @Autowired WaitingEndHistoryRepository historyRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreTableRepository tableRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired SseEmitterRepository emitterRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private final List<Member> members = new ArrayList<>();
    private final List<Store> stores = new ArrayList<>();
    private Member owner;
    private Member customer;
    private Store store;

    @BeforeEach
    void setUp() {
        owner = member("점주", UserType.OWNER);
        customer = member("고객", UserType.CUSTOMER);
        store = store(owner);
    }

    @AfterEach
    void cleanUp() {
        for (Member member : members) {
            for (var emitter : List.copyOf(emitterRepository.findAllByMemberId(member.getId()))) {
                emitter.complete();
                emitterRepository.delete(member.getId(), emitter);
            }
        }
        historyRepository.deleteAllInBatch();
        waitingRepository.deleteAllInBatch();
        for (Store saved : stores) {
            tableRepository.deleteAll(tableRepository.findAllByStoreIdOrderByTableNumberAsc(saved.getId()));
        }
        storeRepository.deleteAll(stores);
        memberRepository.deleteAll(members);
    }

    @Test
    void listIncludesOnlyTodaysActiveTeamsInNumberOrderAndSuitableTableCapacities() throws Exception {
        waiting(store, TODAY, 3, WaitingStatus.CONFIRMED);
        waiting(store, TODAY, 1, WaitingStatus.WAITING);
        waiting(store, TODAY, 2, WaitingStatus.CALLED);
        waiting(store, TODAY, 4, WaitingStatus.CANCELED);
        waiting(store, TODAY, 5, WaitingStatus.NO_SHOW);
        waiting(store, TODAY, 6, WaitingStatus.SEATED);
        waiting(store, TODAY.minusDays(1), 1, WaitingStatus.WAITING);
        waiting(store(member("다른 점주", UserType.OWNER)), TODAY, 1, WaitingStatus.WAITING);
        StoreTable suitable = table(1, 1, 2, TableStatus.ACTIVE);
        table(2, 4, 6, TableStatus.ACTIVE);
        table(3, 1, 2, TableStatus.INACTIVE);

        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings", store.getId())
                        .header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].waitingNumber").value(1))
                .andExpect(jsonPath("$[1].waitingNumber").value(2))
                .andExpect(jsonPath("$[2].waitingNumber").value(3))
                .andExpect(jsonPath("$[2].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].customerName").value("고객"))
                .andExpect(jsonPath("$[0].partySize").value(2))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].compatibleTables.length()").value(1))
                .andExpect(jsonPath("$[0].compatibleTables[0].id").value(suitable.getId()));
    }

    @Test
    void listAndSubscriptionRequireTheActualStoreOwner() throws Exception {
        Long otherOwner = member("다른 점주", UserType.OWNER).getId();
        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings", store.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings", store.getId())
                        .header("X-Member-Id", customer.getId()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings", store.getId())
                        .header("X-Member-Id", otherOwner))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings/subscribe", store.getId())
                        .param("memberId", otherOwner.toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings/subscribe", store.getId())
                        .param("memberId", customer.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(emitterRepository.findAllByMemberId(otherOwner)).isEmpty();
    }

    @Test
    void newWaitingNotifiesOwnerOnceAndRetryDoesNotSendAgain() throws Exception {
        MvcResult stream = subscribe();

        var created = waitingService.createWaiting(customer.getId(), store.getId(), 2);
        var retried = waitingService.createWaiting(customer.getId(), store.getId(), 2);

        assertThat(created.created()).isTrue();
        assertThat(retried.created()).isFalse();
        assertThat(streamContent(stream))
                .contains("event:connect", "event:WAITING_CHANGED", "\"targetId\":" + store.getId())
                .containsOnlyOnce("event:WAITING_CHANGED");
    }

    @Test
    void rollbackDoesNotSendQueueChange() throws Exception {
        MvcResult stream = subscribe();

        transactions.executeWithoutResult(transaction -> {
            waitingService.createWaiting(customer.getId(), store.getId(), 2);
            assertThat(streamContent(stream)).doesNotContain("WAITING_CHANGED");
            transaction.setRollbackOnly();
        });

        assertThat(streamContent(stream)).doesNotContain("WAITING_CHANGED");
        assertThat(waitingRepository.findAllByStoreIdAndWaitingDateAndStatusInOrderByWaitNumberAsc(
                store.getId(), TODAY, WaitingStatus.ACTIVE)).isEmpty();
    }

    @Test
    void cancelRequiresAReasonAndReturnsNoContent() throws Exception {
        Waiting waiting = waiting(store, TODAY, 1, WaitingStatus.WAITING);
        String path = "/api/owner/waitings/" + waiting.getId();
        mockMvc.perform(delete(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "{\"reason\":\"  \"}",
                "{\"reason\":\"" + "가".repeat(301) + "\"}")) {
            mockMvc.perform(delete(path).header("X-Member-Id", owner.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(delete(path).header("X-Member-Id", owner.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"매장 사정\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/user/waitings/{waitingId}", waiting.getId())
                        .header("X-Member-Id", customer.getId()))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.currentPosition").doesNotExist());
    }

    @Test
    void noShowRequiresAManualReasonAndReturnsTheAgreedStatus() throws Exception {
        Waiting waiting = waiting(store, TODAY, 1, WaitingStatus.CALLED);
        String path = "/api/owner/waitings/" + waiting.getId() + "/no-show";
        for (String body : List.of("{}", "{\"reason\":\"NO_RESPONSE\",\"detail\":\"  \"}",
                "{\"reason\":\"AUTO_EXPIRED\",\"detail\":\"자동 종료\"}")) {
            mockMvc.perform(post(path).header("X-Member-Id", owner.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post(path).header("X-Member-Id", owner.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NO_RESPONSE\",\"detail\":\"호출 후 응답 없음\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(waiting.getId()))
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.reason").value("NO_RESPONSE"))
                .andExpect(jsonPath("$.endedAt").value("2030-01-11T00:05:00+09:00"));
    }

    @Test
    void openAndCloseReturnTheStoreWaitingStatus() throws Exception {
        String base = "/api/owner/stores/" + store.getId() + "/waiting/";
        for (String action : List.of("close", "close", "open", "open")) {
            mockMvc.perform(post(base + action).header("X-Member-Id", owner.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(store.getId()))
                    .andExpect(jsonPath("$.waitingAvailable").value(action.equals("open")));
        }
    }

    @Test
    void openAndCloseRequireTheActualStoreOwner() throws Exception {
        Long otherOwner = member("다른 점주", UserType.OWNER).getId();
        for (String action : List.of("open", "close")) {
            String path = "/api/owner/stores/" + store.getId() + "/waiting/" + action;
            mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(path).header("X-Member-Id", customer.getId()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).header("X-Member-Id", otherOwner))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/owner/stores/-1/waiting/" + action).header("X-Member-Id", owner.getId()))
                    .andExpect(status().isNotFound());
        }
        assertThat(storeRepository.findById(store.getId()).orElseThrow().isWaitingAvailable()).isTrue();
    }

    @Test
    void waitingPolicyCanBeReadAndReplaced() throws Exception {
        String path = "/api/owner/stores/" + store.getId() + "/waiting-policy";
        mockMvc.perform(get(path).header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.getId()))
                .andExpect(jsonPath("$.callLimitMinutes").value(10));
        for (String body : List.of("{\"callLimitMinutes\":0}", "{\"callLimitMinutes\":61}", "{}")) {
            mockMvc.perform(put(path).header("X-Member-Id", owner.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put(path).header("X-Member-Id", owner.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"callLimitMinutes\":15}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(store.getId()))
                    .andExpect(jsonPath("$.callLimitMinutes").value(15));
        }
        mockMvc.perform(get(path).header("X-Member-Id", owner.getId()))
                .andExpect(jsonPath("$.callLimitMinutes").value(15));
    }

    @Test
    void waitingPolicyRequiresTheActualStoreOwner() throws Exception {
        Long otherOwner = member("다른 점주", UserType.OWNER).getId();
        String path = "/api/owner/stores/" + store.getId() + "/waiting-policy";
        String body = "{\"callLimitMinutes\":15}";
        mockMvc.perform(get(path).header("X-Member-Id", customer.getId())).andExpect(status().isForbidden());
        mockMvc.perform(put(path).header("X-Member-Id", customer.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(get(path).header("X-Member-Id", otherOwner)).andExpect(status().isNotFound());
        mockMvc.perform(put(path).header("X-Member-Id", otherOwner)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/owner/stores/-1/waiting-policy").header("X-Member-Id", owner.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/owner/stores/-1/waiting-policy").header("X-Member-Id", owner.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getWaitingCallLimitMinutes()).isEqualTo(10);
    }

    @Test
    void callReturnsCallInfoAndNotifiesCustomerAndOwner() throws Exception {
        Waiting waiting = waiting(store, TODAY, 1, WaitingStatus.WAITING);
        mockMvc.perform(put("/api/owner/stores/{storeId}/waiting-policy", store.getId())
                        .header("X-Member-Id", owner.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"callLimitMinutes\":15}"))
                .andExpect(status().isOk());
        MvcResult ownerStream = subscribe();
        MvcResult customerStream = mockMvc.perform(get("/api/user/notifications/subscribe")
                        .param("memberId", customer.getId().toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(post("/api/owner/waitings/{waitingId}/call", waiting.getId())
                        .header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(waiting.getId()))
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.waitNumber").value(1))
                .andExpect(jsonPath("$.callCount").value(1))
                .andExpect(jsonPath("$.calledAt").value("2030-01-11T00:05:00+09:00"))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-11T00:20:00+09:00"));

        assertThat(streamContent(customerStream)).contains(
                "event:WAITING_CALLED",
                "\"targetId\":" + waiting.getId(),
                "\"message\":\"1번 고객님, 00:20까지 테스트 매장에 입장해 주세요.\"",
                "\"waitNumber\":1",
                "\"storeName\":\"테스트 매장\"",
                "\"expiresAt\":\"2030-01-11T00:20:00+09:00\"",
                "event:WAITING_POSITION_CHANGED");
        assertThat(streamContent(ownerStream))
                .contains("event:WAITING_CHANGED", "\"targetId\":" + store.getId());

        mockMvc.perform(post("/api/owner/waitings/{waitingId}/call", waiting.getId())
                        .header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(2));
    }

    @Test
    void callRejectsUncallableStatusAndChecksOwnership() throws Exception {
        Waiting confirmed = waiting(store, TODAY, 1, WaitingStatus.CONFIRMED);
        Waiting waiting = waiting(store, TODAY, 2, WaitingStatus.WAITING);
        Long otherOwner = member("다른 점주", UserType.OWNER).getId();
        String path = "/api/owner/waitings/{waitingId}/call";

        mockMvc.perform(post(path, confirmed.getId()).header("X-Member-Id", owner.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_CALLABLE"));
        mockMvc.perform(post(path, waiting.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, waiting.getId()).header("X-Member-Id", customer.getId()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path, waiting.getId()).header("X-Member-Id", otherOwner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
        mockMvc.perform(post(path, -1L).header("X-Member-Id", owner.getId()))
                .andExpect(status().isNotFound());
    }

    // 응답 만료 경계
    @Test
    void customerRespondsUntilCallExpiryAndOwnerListShowsIt() throws Exception {
        Waiting late = waiting(store, TODAY, 1, WaitingStatus.WAITING);
        Waiting onTime = waiting(store, TODAY, 2, WaitingStatus.WAITING);
        for (Waiting target : List.of(late, onTime)) {
            mockMvc.perform(post("/api/owner/waitings/{waitingId}/call", target.getId())
                            .header("X-Member-Id", owner.getId()))
                    .andExpect(status().isOk());
        }
        jdbc.update("update waiting set call_expires_at = ? where id = ?",
                OffsetDateTime.parse("2030-01-11T00:04:59+09:00"), late.getId());
        jdbc.update("update waiting set call_expires_at = ? where id = ?",
                OffsetDateTime.parse("2030-01-11T00:05:00+09:00"), onTime.getId());

        mockMvc.perform(post("/api/user/waitings/{waitingId}/respond", late.getId())
                        .header("X-Member-Id", customer.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_RESPONDABLE"));
        mockMvc.perform(post("/api/user/waitings/{waitingId}/respond", onTime.getId())
                        .header("X-Member-Id", customer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(onTime.getId()))
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.arrivalIntent").value("COMING"))
                .andExpect(jsonPath("$.respondedAt").value("2030-01-11T00:05:00+09:00"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings", store.getId())
                        .header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(late.getId()))
                .andExpect(jsonPath("$[0].calledAt").isNotEmpty())
                .andExpect(jsonPath("$[0].respondedAt").doesNotExist())
                .andExpect(jsonPath("$[0].arrivalIntent").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(onTime.getId()))
                .andExpect(jsonPath("$[1].status").value("CALLED"))
                .andExpect(jsonPath("$[1].callExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$[1].respondedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].arrivalIntent").value("COMING"));
    }

    @Test
    void seatAfterCallExpiryReturnsSeatedAndNotifies() throws Exception {
        Waiting waiting = waiting(store, TODAY, 1, WaitingStatus.CALLED);
        waiting(store, TODAY, 2, WaitingStatus.WAITING);
        jdbc.update("update waiting set call_expires_at = ? where id = ?",
                OffsetDateTime.parse("2030-01-11T00:04:59+09:00"), waiting.getId());
        MvcResult ownerStream = subscribe();
        MvcResult customerStream = mockMvc.perform(get("/api/user/notifications/subscribe")
                        .param("memberId", customer.getId().toString()))
                .andExpect(request().asyncStarted())
                .andReturn();
        String path = "/api/owner/waitings/{waitingId}/seat";

        mockMvc.perform(post(path, waiting.getId()).header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(waiting.getId()))
                .andExpect(jsonPath("$.status").value("SEATED"))
                .andExpect(jsonPath("$.seatedAt").value("2030-01-11T00:05:00+09:00"));
        mockMvc.perform(post(path, waiting.getId()).header("X-Member-Id", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEATED"))
                .andExpect(jsonPath("$.seatedAt").isNotEmpty());

        assertThat(streamContent(ownerStream)).containsOnlyOnce("event:WAITING_CHANGED");
        assertThat(streamContent(customerStream)).containsOnlyOnce("event:WAITING_POSITION_CHANGED");
        mockMvc.perform(get("/api/owner/stores/{storeId}/waitings", store.getId())
                        .header("X-Member-Id", owner.getId()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].waitingNumber").value(2));
    }

    @Test
    void seatRejectsUncalledTeamAndChecksOwnership() throws Exception {
        Waiting uncalled = waiting(store, TODAY, 1, WaitingStatus.WAITING);
        Waiting called = waiting(store, TODAY, 2, WaitingStatus.CALLED);
        Long otherOwner = member("다른 점주", UserType.OWNER).getId();
        String path = "/api/owner/waitings/{waitingId}/seat";

        mockMvc.perform(post(path, uncalled.getId()).header("X-Member-Id", owner.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_SEATABLE"));
        mockMvc.perform(post(path, called.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, called.getId()).header("X-Member-Id", customer.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
        mockMvc.perform(post(path, called.getId()).header("X-Member-Id", otherOwner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
        mockMvc.perform(post(path, -1L).header("X-Member-Id", owner.getId()))
                .andExpect(status().isNotFound());
    }

    private MvcResult subscribe() throws Exception {
        return mockMvc.perform(get("/api/owner/stores/{storeId}/waitings/subscribe", store.getId())
                        .param("memberId", owner.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private String streamContent(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private Waiting waiting(Store target, LocalDate date, int number, WaitingStatus status) {
        Waiting saved = waitingRepository.save(Waiting.create(customer, target, date, 2, number));
        jdbc.update("update waiting set status = ? where id = ?", status.name(), saved.getId());
        return saved;
    }

    private StoreTable table(int number, int min, int max, TableStatus status) {
        StoreTable table = StoreTable.builder().store(store).tableNumber(number)
                .minCapacity(min).capacity(max).build();
        table.changeStatus(status);
        return tableRepository.save(table);
    }

    private Store store(Member owner) {
        Store store = Store.builder().owner(owner).name("테스트 매장")
                .category(StoreCategory.values()[0]).address("서울시")
                .reservationDurationMinutes(90).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
        store.openWaiting();
        Store saved = storeRepository.save(store);
        stores.add(saved);
        return saved;
    }

    private Member member(String name, UserType type) {
        Member saved = memberRepository.save(Member.builder().name(name).phone("010-0000-0000")
                .userType(type).build());
        members.add(saved);
        return saved;
    }
}
