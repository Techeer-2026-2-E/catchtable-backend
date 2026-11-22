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
        db.update("DELETE FROM waiting_receipt_cancel WHERE member_id=?",member);
        db.update("DELETE FROM waiting_receipt WHERE member_id=?",member);
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
