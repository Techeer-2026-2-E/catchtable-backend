package com.catchtable.reservation.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AvailabilityInvalidationMailboxTest {
    private final long first = -Math.abs(UUID.randomUUID().getMostSignificantBits());
    private final long second = first + 1;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;

    void dirty(long store, String scope) {
        db.queryForList("SELECT dirty_availability(?,?)",store,scope);
    }

    @AfterEach void clean() {
        db.update("DELETE FROM availability_cache_invalidation WHERE store_id IN (?,?)",first,second);
    }

    @Test void outageCoalescesCommitsAndOldAcknowledgmentCannotRemoveNewerChanges() {
        dirty(first,"2030-01-01");
        UUID old=db.queryForObject("SELECT revision FROM availability_cache_invalidation WHERE store_id=?",UUID.class,first);
        for(int i=2;i<=65;i++) dirty(first,java.time.LocalDate.of(2030,1,1).plusDays(i).toString());
        assertThat(db.update("DELETE FROM availability_cache_invalidation WHERE revision=?",old)).isZero();
        assertThat(db.queryForList("SELECT unnest(affected_scopes) FROM availability_cache_invalidation WHERE store_id=?",String.class,first))
                .containsExactly("*");
    }

    @Test void writersTouchingStoresInOppositeOrderCanFinishIndependently() throws Exception {
        var meet = new CyclicBarrier(2);
        try(var threads=Executors.newFixedThreadPool(2)) {
            Callable<Void> clockwise = () -> { changeBoth(first,second,meet); return null; };
            var a=threads.submit(clockwise);
            var b=threads.submit(() -> {changeBoth(second,first,meet);return null;});
            a.get(8,TimeUnit.SECONDS); b.get(8,TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM availability_cache_invalidation WHERE store_id IN (?,?)",Integer.class,first,second))
                .isBetween(2,4);
    }

    @Test void fourContinuousWritersUseAtMostFourLogicalRows() throws Exception {
        var start=new CyclicBarrier(4);
        try(var threads=Executors.newFixedThreadPool(4)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<4;i++) tasks.add(threads.submit(()->{
                start.await(4,TimeUnit.SECONDS);
                for(int j=0;j<100;j++) dirty(first,java.time.LocalDate.of(2030,1,1).plusDays(j).toString());
                return null;
            }));
            for(var task:tasks) task.get(15,TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM availability_cache_invalidation WHERE store_id=?",Integer.class,first)).isBetween(1,4);
        assertThat(db.queryForObject("SELECT count(*) FROM availability_cache_invalidation WHERE store_id=? AND scope='*'",Integer.class,first)).isPositive();
    }

    private void changeBoth(long a,long b,CyclicBarrier meet) {
        tx.executeWithoutResult(state -> {
            dirty(a,"2030-01-01");
            try {meet.await(5,TimeUnit.SECONDS);} catch(Exception e){throw new IllegalStateException(e);}
            dirty(b,"2030-01-02");
        });
    }
}
