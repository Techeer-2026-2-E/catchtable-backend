package com.catchtable.waiting.controller;

import com.catchtable.global.exception.ErrorCode;
import com.catchtable.waiting.WaitingBusinessHourFixture;
import com.catchtable.waiting.dto.WaitingBatchItem;
import com.catchtable.waiting.service.WaitingBatchWriter;
import org.springframework.jdbc.core.ConnectionCallback;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"waiting.intake.batch.enabled=true", "waiting.intake.batch.size=2",
        "waiting.intake.batch.window-ms=500"})
@AutoConfigureMockMvc
@Import(WaitingReceiptHttpTest.TimeConfig.class)
class WaitingBatchHttpTest {
    @Autowired MockMvc mvc;
    @Autowired WaitingBatchWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired MeterRegistry meters;
    private final List<Long> members = new ArrayList<>();
    private long store;

    @BeforeEach
    void prepare() {
        for (int i=0; i<3; i++) {
            members.add(jdbc.queryForObject("""
                    INSERT INTO member(name,phone,user_type) VALUES ('fixture','00000000000',?) RETURNING id
                    """, Long.class, i==0 ? "OWNER" : "CUSTOMER"));
        }
        store=jdbc.queryForObject("""
                INSERT INTO store(owner_id,name,category,address,waiting_available,
                    reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
                VALUES (?, 'fixture','KOREAN','fixture',true,90,30,10) RETURNING id
                """,Long.class,members.getFirst());
        WaitingBusinessHourFixture.saveAllDays(jdbc,store,LocalTime.NOON,LocalTime.of(3,0));
        jdbc.update("INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (?,?,2)",store, LocalDate.now(clock));
    }

    @AfterEach
    void clean() {
        jdbc.update("""
                DELETE FROM waiting_receipt_key k USING waiting_receipt r
                WHERE k.member_id=r.member_id AND k.receipt_id=r.receipt_id AND r.store_id=?
                """,store);
        jdbc.update("DELETE FROM waiting_receipt WHERE store_id=?",store);
        jdbc.update("DELETE FROM waiting_intake_day WHERE store_id=?",store);
        WaitingBusinessHourFixture.clear(jdbc,store);
        jdbc.update("DELETE FROM store WHERE id=?",store);
        members.forEach(id -> jdbc.update("DELETE FROM member WHERE id=?",id));
    }

    private int apply(long member,String key) throws Exception {
        return mvc.perform(post("/api/user/waitings").header("X-Member-Id",member).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":"+store+",\"partyCount\":2}"))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> pair(long a,long b) throws Exception {
        var start=new CyclicBarrier(2);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(() -> {start.await(5,TimeUnit.SECONDS); return apply(a,"first");});
            var second=workers.submit(() -> {start.await(5,TimeUnit.SECONDS); return apply(b,"second");});
            return List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
    }

    private WaitingBatchItem item(long m, String key, int party) {
        return new WaitingBatchItem(new WaitingBatchItem.Key(m, key), party);
    }

    @Test
    void mixedItemsKeepValidAdmissionsAliasesAndCapacityInOrder() {
        long a=members.get(1), b=members.get(2), c=members.getFirst();
        var decisions=writer.write(store, List.of(item(a,"a",2),item(a,"a",2),item(a,"a",3),
                item(a,"unbound",3),item(-1,"missing",2),item(a,"alias",2),item(b,"b",3),
                item(c,"over",2),item(c,"over",3))).orElseThrow();
        var first=decisions.get(0).receipt();
        assertThat(first.created()).isTrue();
        assertThat(first.response().intakeSequence()).isEqualTo(1);
        for(int i : List.of(1,5)) {
            assertThat(decisions.get(i).receipt().created()).isFalse();
            assertThat(decisions.get(i).receipt().response()).isEqualTo(first.response());
        }
        assertThat(decisions.get(2).error()).isEqualTo(ErrorCode.WAITING_RECEIPT_KEY_CONFLICT);
        assertThat(decisions.get(3).error()).isEqualTo(ErrorCode.DUPLICATE_ACTIVE_WAITING);
        assertThat(decisions.get(4).error()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThat(decisions.get(6).receipt().response().intakeSequence()).isEqualTo(2);
        assertThat(decisions.get(7).error()).isEqualTo(ErrorCode.WAITING_INTAKE_FULL);
        assertThat(decisions.get(8).error()).isEqualTo(ErrorCode.WAITING_INTAKE_FULL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_receipt_key WHERE member_id IN (?,?)",
                Integer.class,a,b)).isEqualTo(3);
        jdbc.update("UPDATE store SET waiting_available=false WHERE id=?",store);
        var closed=writer.write(store,List.of(item(c,"new",2),item(a,"alias2",2),item(b,"b",3))).orElseThrow();
        assertThat(closed.get(0).error()).isEqualTo(ErrorCode.WAITING_CLOSED);
        assertThat(closed.get(1).receipt().response()).isEqualTo(first.response());
        assertThat(closed.get(2).receipt().created()).isFalse();
    }

    @Test
    void overlappingBatchesWithOppositeMemberOrdersDoNotDoubleAllocate() throws Exception {
        long a=members.get(1), b=members.get(2);
        var ab=List.of(item(a,"ab-a",2),item(b,"ab-b",2));
        var ba=List.of(item(b,"ba-b",2),item(a,"ba-a",2));
        var start=new CyclicBarrier(2);
        try (var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(() -> {start.await(); return writer.write(store,ab).orElseThrow();});
            var second=threads.submit(() -> {start.await(); return writer.write(store,ba).orElseThrow();});
            var results=new ArrayList<>(first.get(8,TimeUnit.SECONDS));
            results.addAll(second.get(8,TimeUnit.SECONDS));
            assertThat(results.stream().filter(d -> d.receipt().created()).count()).isEqualTo(2);
            assertThat(results.stream().map(d -> d.receipt().response().receiptId()).distinct().count()).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT pending_count FROM waiting_intake_day WHERE store_id=?",Integer.class,store))
                .isEqualTo(2);
    }

    @Test
    void commitFailureAcknowledgesNeitherRequestAndLeavesBothKeysReusable() throws Exception {
        long a=members.get(1), b=members.get(2);
        // This trigger runs at COMMIT, after the writer has built the entire reply list.
        jdbc.execute("""
            CREATE FUNCTION batch_test_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_advisory_xact_lock(9837,1);
                RAISE EXCEPTION 'synthetic commit failure';
            END; $$;
            """);
        try {
            jdbc.execute("""
                CREATE CONSTRAINT TRIGGER batch_test_fail_commit AFTER INSERT ON waiting_receipt_key
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.member_id=%d)
                EXECUTE FUNCTION batch_test_fail_commit()
                """.formatted(a));
            var start=new CyclicBarrier(2);
            try (var threads=Executors.newFixedThreadPool(2)) {
                jdbc.execute((ConnectionCallback<Void>) holder -> {
                    try (var s=holder.createStatement()) {
                        s.execute("SELECT pg_advisory_lock(9837,1)");
                        var first=threads.submit(() -> {start.await(); return apply(a,"first");});
                        var second=threads.submit(() -> {start.await(); return apply(b,"second");});
                        try {
                            boolean blocked=false;
                            for (int i=0;i<150 && !blocked;i++) {
                                blocked=Boolean.TRUE.equals(jdbc.queryForObject("""
                                    SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory'
                                        AND classid=9837 AND objid=1 AND NOT granted)
                                    """,Boolean.class));
                                if (!blocked) Thread.sleep(20);
                            }
                            assertThat(blocked).isTrue();
                            assertThat(first.isDone()).isFalse();
                            assertThat(second.isDone()).isFalse();
                            assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_receipt WHERE store_id=?",
                                    Integer.class,store)).isZero();
                        } finally {
                            s.execute("SELECT pg_advisory_unlock(9837,1)");
                        }
                        assertThat(first.get(8,TimeUnit.SECONDS)).isBetween(500,599);
                        assertThat(second.get(8,TimeUnit.SECONDS)).isBetween(500,599);
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                        throw new AssertionError(e);
                    }
                    return null;
                });
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS batch_test_fail_commit ON waiting_receipt_key");
            jdbc.execute("DROP FUNCTION batch_test_fail_commit()");
        }
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM waiting_intake_day WHERE store_id=?",Long.class,store)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_receipt_key WHERE member_id IN (?,?)",
                Integer.class,a,b)).isZero();
        assertThat(pair(a,b)).containsExactly(202,202);
    }

    @Test
    void twoApplicantsShareACommittedBatch() throws Exception {
        var old=meters.find("waiting.intake.batch.requests").summary();
        long before=old==null ? 0 : old.count();
        assertThat(pair(members.get(1),members.get(2))).containsExactly(202,202);
        var meter=meters.find("waiting.intake.batch.requests").summary();
        assertThat(meter).isNotNull();
        assertThat(meter.count()-before).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT intake_sequence FROM waiting_receipt WHERE store_id=? ORDER BY 1",
                Long.class,store)).containsExactly(1L,2L);
    }
}
