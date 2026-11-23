package com.catchtable.waiting.service;

import com.catchtable.waiting.dto.WaitingLane;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReceiptRegistrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ReceiptRegistration service;
    @Autowired com.catchtable.waiting.repository.ReceiptWorkRepository work;
    @Autowired WaitingService restaurant;
    long member,store;
    WaitingLane lane;
    UUID id;

    @BeforeEach void setup() {
        member=db.queryForObject("INSERT INTO member(name,phone,user_type) VALUES ('fixture','000','CUSTOMER') RETURNING id",Long.class);
        store=db.queryForObject("""
                INSERT INTO store(owner_id,name,category,address,waiting_available,reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
                VALUES (?,'fixture','KOREAN','fixture',false,90,30,10) RETURNING id
                """,Long.class,member);
        lane=new WaitingLane(store,LocalDate.of(2030,1,1));
        db.update("INSERT INTO waiting_intake_day(store_id,received_date,capacity,pending_count,last_sequence) VALUES(?,?,2,1,1)",store,lane.day());
        id=UUID.randomUUID();
        db.update("""
                INSERT INTO waiting_receipt(member_id,receipt_id,store_id,received_date,intake_sequence,party_count,accepted_at)
                VALUES(?,?,?,?,1,2,now())
                """,member,id,store,lane.day());
    }
    @AfterEach void cleanup() {
        db.update("DELETE FROM waiting_receipt_cancel c USING waiting_receipt r WHERE c.member_id=r.member_id AND c.receipt_id=r.receipt_id AND r.store_id=?",store);
        db.update("DELETE FROM waiting_receipt WHERE store_id=?",store);
        db.update("DELETE FROM waiting_intake_day WHERE store_id=?",store);
        db.update("DELETE FROM waiting_end_history WHERE waiting_id IN (SELECT id FROM waiting WHERE store_id=?)",store);
        db.update("DELETE FROM waiting WHERE store_id=?",store);
        db.update("DELETE FROM store WHERE id=?",store);
        db.update("DELETE FROM member WHERE id=?",member);
    }
    @Test void expiredClaimCannotWriteAfterTakeoverAndOriginalDaySurvivesClosure() {
        var old=service.claim(lane,id).orElseThrow();
        assertThat(service.claim(lane,id)).isEmpty();
        db.update("UPDATE waiting_receipt SET lease_until=now()-interval '1 second' WHERE receipt_id=?",id);
        var replacement=service.claim(lane,id).orElseThrow();
        assertThat(service.complete(old)).isFalse();
        assertThat(service.complete(replacement)).isTrue();
        assertThat(service.complete(replacement)).isFalse();
        var result=service.view(member,id);
        assertThat(result.waitNumber()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT waiting_date FROM waiting WHERE id=?",LocalDate.class,result.waitingId())).isEqualTo(lane.day());
        assertThat(db.queryForObject("SELECT retire_waiting_intake(?,?)",Boolean.class,store,lane.day())).isTrue();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void aClaimedPrefixKeepsReceiptOrderAndHonorsAMiddleCancellation() {
        long nextMember = db.queryForObject("INSERT INTO member(name,phone,user_type) VALUES ('fixture','000','CUSTOMER') RETURNING id",Long.class);
        long lastMember = db.queryForObject("INSERT INTO member(name,phone,user_type) VALUES ('fixture','000','CUSTOMER') RETURNING id",Long.class);
        UUID middle = UUID.randomUUID(), last = UUID.randomUUID();
        db.update("UPDATE waiting_intake_day SET capacity=3,pending_count=3,last_sequence=3 WHERE store_id=?",store);
        db.update("""
                INSERT INTO waiting_receipt(member_id,receipt_id,store_id,received_date,intake_sequence,party_count,accepted_at)
                VALUES (?,?,?,?,2,2,now()),(?,?,?,?,3,2,now())
                """,nextMember,middle,store,lane.day(),lastMember,last,store,lane.day());
        var claim = service.claimPrefix(lane,null,3).orElseThrow();
        assertThat(claim.rows()).hasSize(3);
        service.cancelIntent(nextMember,middle);
        assertThat(service.complete(claim)).isTrue();
        assertThat(service.complete(claim)).isFalse();
        assertThat(service.view(member,id).waitNumber()).isEqualTo(1);
        assertThat(service.view(nextMember,middle).status()).isEqualTo("WITHDRAWN");
        assertThat(service.view(lastMember,last).waitNumber()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT pending_count FROM waiting_intake_day WHERE store_id=?",Integer.class,store)).isZero();
    }

    @Test void anActiveRestaurantNumberPreventsANewKeyUntilTheRestaurantActuallyEndsIt() {
        assertThat(service.complete(service.claim(lane,id).orElseThrow())).isTrue();
        var actual=service.view(member,id);
        String sql="""
                SELECT count(*) FROM append_fresh_waiting_receipts(?,?,now(),ARRAY[?]::bigint[],
                    ARRAY[?]::uuid[],ARRAY[2],ARRAY['new-key'])
                """;
        UUID next=UUID.randomUUID();
        assertThat(db.queryForObject(sql,Integer.class,store,lane.day(),member,next)).isZero();
        assertThat(db.queryForObject("SELECT last_sequence FROM waiting_intake_day WHERE store_id=?",Integer.class,store)).isEqualTo(1);
        restaurant.cancelWaiting(member,actual.waitingId());
        assertThat(db.queryForObject(sql,Integer.class,store,lane.day(),member,next)).isOne();
        assertThat(service.view(member,next).intakeSequence()).isEqualTo(2);
        db.update("DELETE FROM waiting_receipt_key WHERE member_id=?",member);
    }

    @Test void cancelIntentOutlivesADeadWorkerAndCanBeResolvedWithoutRedis() {
        var old=service.claim(lane,id).orElseThrow();
        service.cancelIntent(member,id);
        assertThat(service.view(member,id).status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(db.queryForObject("SELECT retire_waiting_intake(?,?)",Boolean.class,store,lane.day())).isFalse();
        db.update("UPDATE waiting_receipt SET lease_until=now()-interval '1 second' WHERE receipt_id=?",id);
        assertThat(service.settleOneCancel(lane)).isTrue();
        assertThat(service.complete(old)).isFalse();
        assertThat(service.view(member,id).status()).isEqualTo("WITHDRAWN");
        assertThat(service.settleOneCancel(lane)).isFalse();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void theOldestReceiptBoundsAdmissionPriorityEvenDuringContinuedIntake() {
        // 512건 이상이면 '최신 접수 100ms 이내' 조건을 보지 않는다. 가장 오래된 접수의 상한만 검증한다.
        db.update("UPDATE waiting_intake_day SET capacity=512,last_sequence=512,pending_count=512 WHERE store_id=?",store);
        db.update("""
                WITH new_members AS (
                    INSERT INTO member(name,phone,user_type) SELECT 'fixture','000','CUSTOMER' FROM generate_series(1,511)
                    RETURNING id
                )
                INSERT INTO waiting_receipt(member_id,receipt_id,store_id,received_date,intake_sequence,party_count,accepted_at)
                SELECT id,gen_random_uuid(),?,?,1+row_number() OVER(ORDER BY id),2,clock_timestamp() FROM new_members
                """,store,lane.day());
        db.update("UPDATE waiting_receipt SET accepted_at=clock_timestamp() WHERE store_id=?",store);
        assertThat(work.receivingBurst(lane,1800)).isTrue();
        db.update("UPDATE waiting_receipt SET accepted_at=clock_timestamp()-interval '2 seconds' WHERE receipt_id=?",id);
        assertThat(work.receivingBurst(lane,1800)).isFalse();
    }

    @Test void permitsAreClusterWideAndHeldForWholeRegistrationTransaction() throws Exception {
        var claimed=service.claim(lane,id).orElseThrow();
        try(var c=java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.execute("SELECT id FROM waiting_registration_permit FOR UPDATE"); }
            assertThat(service.complete(claimed)).isFalse();
            assertThat(service.view(member,id).status()).isEqualTo("PENDING");
            c.rollback();
        }
        assertThat(service.complete(service.claim(lane,id).orElseThrow())).isTrue();
    }
}
