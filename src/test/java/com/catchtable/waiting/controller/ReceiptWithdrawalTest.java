package com.catchtable.waiting.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReceiptWithdrawalTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;

    @Test void withdrawsOnceAndKeepsTheSameReceipt() throws Exception {
        Long m=db.queryForObject("INSERT INTO member(name,phone,user_type) VALUES ('fixture','000','OWNER') RETURNING id",Long.class);
        Long s=db.queryForObject("""
            INSERT INTO store(owner_id,name,category,address,waiting_available,reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
            VALUES (?,'fixture','KOREAN','fixture',true,90,30,10) RETURNING id
            """,Long.class,m);
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO waiting_intake_day VALUES (?,current_date,2,1,1,true)",s);
        db.update("""
            INSERT INTO waiting_receipt(member_id,receipt_id,store_id,received_date,intake_sequence,party_count,accepted_at)
            VALUES (?,?,?,current_date,1,2,now())
            """,m,id,s);
        String path="/api/user/waitings/receipts/"+id;
        mvc.perform(delete(path).header("X-Member-Id",-1)).andExpect(status().isNotFound());
        for(int i=0;i<2;i++) mvc.perform(delete(path).header("X-Member-Id",m)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mvc.perform(get(path).header("X-Member-Id",m)).andExpect(jsonPath("$.intakeSequence").value(1));
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT pending_count FROM waiting_intake_day WHERE store_id=?",Integer.class,s)).isZero();
    }
}
