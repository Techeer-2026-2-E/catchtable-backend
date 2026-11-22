package com.catchtable.waiting.controller;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.catchtable.waiting.service.WaitingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WaitingReceiptHttpTest {
    private static final Instant START = Instant.parse("2031-02-01T14:59:58Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String POST_PATH = "/api/user/waitings";

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock testClock() {
            Clock clock = mock(Clock.class);
            when(clock.getZone()).thenReturn(SEOUL);
            when(clock.instant()).thenReturn(START);
            return clock;
        }
    }

    @Autowired WaitingService legacyService;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired TransactionTemplate transactions;
    private long store;
    private long first;
    private long second;

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(START);
        first = member();
        second = member();
        store = jdbc.queryForObject("""
                INSERT INTO store(owner_id,name,category,address,waiting_available,
                    reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
                VALUES (?, 'fixture', 'KOREAN','fixture',true,90,30,10) RETURNING id
                """, Long.class, first);
        WaitingBusinessHourFixture.saveAllDays(jdbc, store, LocalTime.of(12,0), LocalTime.of(3,0));
        jdbc.update("INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (?,?,1)",
                store, LocalDate.now(clock));
    }

    private long member() {
        return jdbc.queryForObject("""
                INSERT INTO member(name,phone,user_type) VALUES ('fixture','00000000000','CUSTOMER') RETURNING id
                """, Long.class);
    }

    @AfterEach
    void clean() {
        when(clock.instant()).thenReturn(START);
        jdbc.update("DELETE FROM waiting_receipt_cancel WHERE member_id IN (?,?)", first, second);
        jdbc.update("DELETE FROM waiting_receipt_key WHERE member_id IN (?,?)", first, second);
        jdbc.update("DELETE FROM waiting_receipt WHERE store_id=?", store);
        jdbc.update("DELETE FROM waiting_intake_day WHERE store_id=?", store);
        jdbc.update("DELETE FROM waiting WHERE store_id=?", store);
        WaitingBusinessHourFixture.clear(jdbc,store);
        jdbc.update("DELETE FROM store WHERE id=?", store);
        jdbc.update("DELETE FROM member WHERE id IN (?,?)", first, second);
    }

    private ResultActions apply(long member, String key, int party) throws Exception {
        var request = post(POST_PATH).header("X-Member-Id", member).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":" + store + ",\"partyCount\":" + party + "}");
        if (key != null) request.header("Idempotency-Key",key);
        return mvc.perform(request);
    }

    @Test
    void persistentStoreKeepsItsRouteAfterMidnightAndStopsOnlyAfterDraining() throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDate tomorrow = today.plusDays(1);
        assertThat(jdbc.queryForObject("SELECT enable_waiting_intake(?,?,?)", String.class, store, today, 1))
                .isEqualTo("ALREADY_ENABLED");
        String firstTicket = apply(first, "persistent-before", 2).andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
        when(clock.instant()).thenReturn(START.plusSeconds(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_intake_day WHERE store_id=? AND received_date=?",
                Integer.class, store, tomorrow)).isZero();
        String nextTicket = apply(second, "persistent-after", 2).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedDate").value(tomorrow.toString()))
                .andExpect(jsonPath("$.intakeSequence").value(1)).andReturn().getResponse().getHeader("Location");
        apply(first, "persistent-before", 2).andExpect(status().isOk()).andExpect(header().string("Location", firstTicket));
        mvc.perform(delete(nextTicket).header("X-Member-Id", second)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT retire_waiting_intake(?,?)", Boolean.class, store, tomorrow)).isFalse();
        mvc.perform(delete(firstTicket).header("X-Member-Id", first)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT retire_waiting_intake(?,?)", Boolean.class, store, tomorrow)).isTrue();
        when(clock.instant()).thenReturn(START.plusSeconds(86404));
        apply(first, "persistent-reverted", 2).andExpect(status().isCreated()).andExpect(jsonPath("$.waitNumber").value(1));
        mvc.perform(get(nextTicket).header("X-Member-Id", second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void fullDayStillResolvesOriginalAndAliasBeforeClosureOrCapacityChecks() throws Exception {
        String location = apply(first,"first",2).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.intakeSequence").value(1)).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.waitNumber").doesNotExist()).andExpect(header().exists("Retry-After"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith(POST_PATH + "/receipts/");
        apply(second,"full",2).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        jdbc.update("UPDATE store SET waiting_available=false WHERE id=?",store);
        apply(first,"another",2).andExpect(status().isOk()).andExpect(header().string("Location",location));
        apply(first,"first",3).andExpect(status().isConflict());
        apply(first,"different",3).andExpect(status().isConflict());
        when(clock.instant()).thenReturn(START.plusSeconds(4));
        for (String key : new String[]{"first","another"}) {
            apply(first,key,2).andExpect(status().isOk()).andExpect(header().string("Location",location))
                    .andExpect(jsonPath("$.receivedDate").value("2031-02-01"));
        }
        mvc.perform(get(location).header("X-Member-Id",first)).andExpect(status().isOk());
        mvc.perform(get(location).header("X-Member-Id",second)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting WHERE store_id=?",Integer.class,store)).isZero();
    }

    @Test
    void rejectedAndRolledBackApplicationsLeaveTheFirstSlotAvailable() throws Exception {
        apply(first,null,2).andExpect(status().isBadRequest());
        apply(first," ",2).andExpect(status().isBadRequest());
        apply(first,"x".repeat(129),2).andExpect(status().isBadRequest());
        apply(first,"invalid-party",21).andExpect(status().isBadRequest());
        apply(-1,"missing",2).andExpect(status().isNotFound());
        jdbc.update("UPDATE store SET waiting_available=false WHERE id=?",store);
        apply(first,"closed",2).andExpect(status().isConflict());
        jdbc.update("UPDATE store SET waiting_available=true WHERE id=?",store);
        jdbc.update("UPDATE business_hour SET open_time='09:00',closing_time='10:00' WHERE store_id=?",store);
        apply(first,"hours",2).andExpect(status().isConflict());
        jdbc.update("UPDATE business_hour SET open_time='12:00',closing_time='03:00' WHERE store_id=?",store);
        transactions.executeWithoutResult(tx -> {
            try {
                apply(first,"rollback",2).andExpect(status().isAccepted());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_receipt_key WHERE member_id=?",
                Integer.class, first)).isZero();
        apply(second,"ok",2).andExpect(status().isAccepted()).andExpect(jsonPath("$.intakeSequence").value(1));
    }

    @Test
    void concurrentFirstRequestsOpenExactlyOneInheritedDayAndFenceLegacyInserts() throws Exception {
        LocalDate today = LocalDate.now(clock);
        jdbc.update("DELETE FROM waiting_intake_day WHERE store_id=?", store);
        assertThat(jdbc.queryForObject("SELECT enable_waiting_intake(?,?,2)", String.class, store, today))
                .isEqualTo("ENABLED");
        when(clock.instant()).thenReturn(START.plusSeconds(4));
        LocalDate tomorrow = today.plusDays(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO waiting(member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,created_at,updated_at)
                VALUES (?,?,?,2,1,1,'WAITING',now(),now())
                """, first, store, tomorrow)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Missing durable receipt binding");
        var start = new CyclicBarrier(2);
        try (var callers = Executors.newFixedThreadPool(2)) {
            List<Future<Long>> requests = new java.util.ArrayList<>();
            for (long memberId : new long[]{first, second}) {
                requests.add(callers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    String location = apply(memberId, "inherited-" + memberId, 2)
                            .andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
                    return jdbc.queryForObject("SELECT intake_sequence FROM waiting_receipt WHERE receipt_id=?::uuid",
                            Long.class, location.substring(location.lastIndexOf('/') + 1));
                }));
            }
            assertThat(List.of(requests.get(0).get(10, TimeUnit.SECONDS), requests.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1L, 2L);
        }
        assertThat(jdbc.queryForObject("SELECT pending_count FROM waiting_intake_day WHERE store_id=? AND received_date=?",
                Integer.class, store, tomorrow)).isEqualTo(2);
    }

    @Test
    void switchesAtSeoulMidnightAndKeepsReceiptsAfterRevertingTheNewDay() throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDate tomorrow = today.plusDays(1);
        String firstTicket = apply(first, "yesterday", 2).andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
        jdbc.update("INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (?,?,1)",
                store, tomorrow);
        when(clock.instant()).thenReturn(START.plusSeconds(4));
        String nextTicket = apply(second, "today", 2).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedDate").value(tomorrow.toString()))
                .andExpect(jsonPath("$.intakeSequence").value(1)).andReturn().getResponse().getHeader("Location");
        String retire = "SELECT retire_waiting_intake(?,?)";
        assertThat(jdbc.queryForObject(retire, Boolean.class, store, tomorrow)).isFalse();
        mvc.perform(delete(nextTicket).header("X-Member-Id", second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        assertThat(jdbc.queryForObject(retire, Boolean.class, store, tomorrow)).isTrue();
        apply(second, "today", 2).andExpect(status().isOk()).andExpect(header().string("Location", nextTicket))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        apply(first, "yesterday", 2).andExpect(status().isOk()).andExpect(header().string("Location", firstTicket))
                .andExpect(jsonPath("$.receivedDate").value(today.toString()));
        apply(second, "next-application", 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.waitNumber").value(1)).andExpect(jsonPath("$.receiptId").doesNotExist());
    }

    @Test
    void choosesTheNewDayIfTheStoreLockSpansSeoulMidnight() throws Exception {
        // The route hint sees a ledger day. Validation under the store lock sees the next, legacy day.
        when(clock.instant()).thenReturn(START, START.plusSeconds(4));
        apply(second, "at-midnight", 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.waitNumber").value(1));
        assertThat(jdbc.queryForObject("SELECT waiting_date FROM waiting WHERE store_id=?", LocalDate.class, store))
                .isEqualTo(LocalDate.of(2031,2,2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_receipt WHERE store_id=?", Integer.class, store))
                .isZero();
    }

    @Test
    void keylessServiceEntryAlsoRespectsTheLedgerDay() {
        assertThatThrownBy(() -> legacyService.createWaiting(first, store, 2))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting WHERE store_id=?", Integer.class, store)).isZero();
    }

    @Test
    void cannotEnableReceiptRouteAfterLegacyRegistrationOnSameDay() throws Exception {
        jdbc.update("DELETE FROM waiting_intake_day WHERE store_id=?", store);
        apply(first, null, 2).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (?,?,1)",
                store, LocalDate.now(clock))).isInstanceOf(DataIntegrityViolationException.class);
        apply(first, "unused-legacy-key", 2).andExpect(status().isOk()).andExpect(jsonPath("$.waitNumber").value(1));
    }

    @Test
    void simultaneousKeysForSameMemberReturnOneReceipt() throws Exception {
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Integer> one = () -> {
                start.await(5,TimeUnit.SECONDS);
                return apply(first,Thread.currentThread().getName(),2).andReturn().getResponse().getStatus();
            };
            var a = workers.submit(one);
            var b = workers.submit(one);
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202,200);
        }
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT receipt_id) FROM waiting_receipt_key WHERE member_id=?",
                Integer.class,first)).isEqualTo(1);
    }
}
