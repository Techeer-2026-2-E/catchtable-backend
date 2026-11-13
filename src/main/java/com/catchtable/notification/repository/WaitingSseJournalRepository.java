package com.catchtable.notification.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL only. Joins the caller's transaction; never starts, commits, or suspends one. */
@Repository
@RequiredArgsConstructor
public class WaitingSseJournalRepository {
    private final JdbcTemplate jdbc;

    public record RootRow(UUID id, String kind, Long waitingId, Integer callCount, Long storeId,
                          LocalDate date, Long memberId, String type, String message, boolean expanded) {}
    public record TargetRow(UUID id, UUID rootId, Long memberId, Long waitingId, Integer callCount,
                            String generation, String type, String message) {}
    public record AttemptRow(UUID id, UUID targetId, String status) {}
    public record WaitingRow(int callCount, String status) {}
    public record QueueRow(Long id, Long memberId, int callCount, String status) {}

    public Integer findCallCount(Long waiting) {
        return jdbc.queryForList("SELECT call_count FROM waiting WHERE id=?",Integer.class,waiting).stream().findFirst().orElse(null);
    }
    public Long findStoreId(Long waiting) {
        return jdbc.queryForList("SELECT store_id FROM waiting WHERE id=?",Long.class,waiting).stream().findFirst().orElse(null);
    }
    public void lockCapacity() {
        jdbc.queryForObject("SELECT retained_roots FROM waiting_sse_capacity WHERE id=1 FOR UPDATE",Long.class);
    }
    public boolean rootExists(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM waiting_sse_root WHERE event_key=?)",Boolean.class,key));
    }
    public boolean reserveCapacity(int limit) {
        return jdbc.update("UPDATE waiting_sse_capacity SET retained_roots=retained_roots+1 WHERE id=1 AND retained_roots < ?",limit)==1;
    }
    public void insertRoot(UUID id, String key, String kind, Long waiting, Integer callCount, Integer recallCount,
                           Long store, LocalDate date, Long member, String type, String message) {
        jdbc.update("""
                INSERT INTO waiting_sse_root(id,event_key,kind,waiting_id,call_count,recall_count,store_id,waiting_date,member_id,event_type,message)
                VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """,id,key,kind,waiting,callCount,recallCount,store,date,member,type,message);
    }
    public Optional<RootRow> lockNextRoot() {
        return jdbc.query("""
                SELECT * FROM waiting_sse_root WHERE status='PENDING' OR (status='CLAIMED' AND lease_until < clock_timestamp())
                ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(r,i)->root(r)).stream().findFirst();
    }
    public List<AttemptRow> markedAttempts(UUID root) {
        return jdbc.query("""
                SELECT a.id,a.target_id,a.status FROM waiting_sse_attempt a JOIN waiting_sse_target t ON t.id=a.target_id
                WHERE t.root_id=? AND a.status='MARKED'
                """,(r,i)->attempt(r),root);
    }
    public void markAttemptUnknown(UUID id,String reason) {
        jdbc.update("UPDATE waiting_sse_attempt SET status='UNKNOWN',reason=? WHERE id=?",reason,id);
    }
    public void setTargetState(UUID id,String status,String reason) {
        jdbc.update("UPDATE waiting_sse_target SET status=?,reason=? WHERE id=?",status,reason,id);
    }
    public void claimRoot(UUID root,UUID token,long leaseSeconds) {
        jdbc.update("UPDATE waiting_sse_root SET status='CLAIMED',claim_token=?,lease_until=clock_timestamp()+(? * interval '1 second') WHERE id=?",token,leaseSeconds,root);
    }
    public boolean lockClaim(UUID root,UUID token) {
        return !jdbc.queryForList("SELECT id FROM waiting_sse_root WHERE id=? AND claim_token=? AND status='CLAIMED' AND lease_until>clock_timestamp() FOR UPDATE",UUID.class,root,token).isEmpty();
    }
    public boolean expanded(UUID root) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT expanded_at IS NOT NULL FROM waiting_sse_root WHERE id=?",Boolean.class,root));
    }
    public List<QueueRow> activeQueue(Long store,LocalDate date,int limit) {
        return jdbc.query("""
                SELECT id,member_id,call_count,status FROM waiting WHERE store_id=? AND waiting_date=?
                  AND status IN ('WAITING','CALLED','CONFIRMED') ORDER BY queue_order,id LIMIT ?
                """,(r,i)->new QueueRow(r.getLong("id"),r.getLong("member_id"),r.getInt("call_count"),r.getString("status")),store,date,limit);
    }
    public void insertTarget(TargetRow target,String status,String reason) {
        jdbc.update("""
                INSERT INTO waiting_sse_target(id,root_id,member_id,waiting_id,call_count,connection_generation,event_type,message,status,reason)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?,?)
                """,target.id(),target.rootId(),target.memberId(),target.waitingId(),target.callCount(),target.generation(),target.type(),target.message(),status,reason);
    }
    public void completeExpansion(UUID root,int recipients,int targets,OffsetDateTime at) {
        jdbc.update("UPDATE waiting_sse_root SET expected_recipients=?,expected_targets=?,expanded_at=? WHERE id=?",recipients,targets,at,root);
    }
    public List<TargetRow> readyTargets(UUID root) {
        return jdbc.query("SELECT * FROM waiting_sse_target WHERE root_id=? AND status='READY' ORDER BY id",(r,i)->target(r),root);
    }
    public String lockTargetStatus(UUID target) {
        return jdbc.queryForObject("SELECT status FROM waiting_sse_target WHERE id=? FOR UPDATE",String.class,target);
    }
    public void insertAttempt(UUID id,UUID target,UUID token) {
        jdbc.update("INSERT INTO waiting_sse_attempt(id,target_id,claim_token,status) VALUES(?,?,?,'MARKED')",id,target,token);
        jdbc.update("UPDATE waiting_sse_target SET status='MARKED' WHERE id=?",target);
    }
    public void renewLease(UUID root,long leaseSeconds) {
        jdbc.update("UPDATE waiting_sse_root SET lease_until=clock_timestamp()+(? * interval '1 second') WHERE id=?",leaseSeconds,root);
    }
    public boolean authorized(UUID attempt,UUID token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM waiting_sse_attempt a JOIN waiting_sse_target t ON t.id=a.target_id
                 JOIN waiting_sse_root r ON r.id=t.root_id WHERE a.id=? AND a.status='MARKED'
                   AND r.status='CLAIMED' AND r.claim_token=? AND r.lease_until>clock_timestamp())
                """,Boolean.class,attempt,token));
    }
    public UUID rootOfAttempt(UUID attempt) {
        return jdbc.queryForObject("SELECT t.root_id FROM waiting_sse_attempt a JOIN waiting_sse_target t ON t.id=a.target_id WHERE a.id=?",UUID.class,attempt);
    }
    public void lockRoot(UUID root) {
        jdbc.queryForObject("SELECT id FROM waiting_sse_root WHERE id=? FOR UPDATE",UUID.class,root);
    }
    public AttemptRow lockAttempt(UUID attempt) {
        return jdbc.queryForObject("SELECT id,target_id,status FROM waiting_sse_attempt WHERE id=? FOR UPDATE",(r,i)->attempt(r),attempt);
    }
    public void saveAttemptResult(UUID attempt,String outcome,OffsetDateTime observedAt) {
        jdbc.update("UPDATE waiting_sse_attempt SET status=?,reason=?,observed_at=? WHERE id=?",outcome,outcome,observedAt,attempt);
    }
    public long unfinishedTargetCount(UUID root) {
        return jdbc.queryForObject("SELECT count(*) FROM waiting_sse_target WHERE root_id=? AND status IN ('READY','MARKED')",Long.class,root);
    }
    public long unknownTargetCount(UUID root) {
        return jdbc.queryForObject("SELECT count(*) FROM waiting_sse_target WHERE root_id=? AND status='UNKNOWN'",Long.class,root);
    }
    public void finishRoot(UUID root,String status,OffsetDateTime at) {
        jdbc.update("UPDATE waiting_sse_root SET status=?,completed_at=?,lease_until=NULL WHERE id=?",status,at,root);
    }
    public Optional<WaitingRow> waitingState(Long waiting) {
        return jdbc.query("SELECT call_count,status FROM waiting WHERE id=?",(r,i)->new WaitingRow(r.getInt("call_count"),r.getString("status")),waiting).stream().findFirst();
    }
    public OffsetDateTime callExpiry(Long waiting) {
        return jdbc.queryForObject("SELECT call_expires_at FROM waiting WHERE id=?",OffsetDateTime.class,waiting);
    }
    public void observe(String key,UUID root,UUID target,UUID attempt,String phase,String reason,String stage,OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO waiting_sse_observation(observation_key,root_id,target_id,attempt_id,phase,reason,last_confirmed_stage,observed_at)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(observation_key) DO NOTHING
                """,key,root,target,attempt,phase,reason,stage,at);
    }
    private static RootRow root(ResultSet r) throws SQLException {
        return new RootRow(r.getObject("id",UUID.class),r.getString("kind"),(Long)r.getObject("waiting_id"),(Integer)r.getObject("call_count"),
                (Long)r.getObject("store_id"),r.getObject("waiting_date",LocalDate.class),(Long)r.getObject("member_id"),r.getString("event_type"),r.getString("message"),r.getObject("expanded_at")!=null);
    }
    private static TargetRow target(ResultSet r) throws SQLException {
        return new TargetRow(r.getObject("id",UUID.class),r.getObject("root_id",UUID.class),(Long)r.getObject("member_id"),(Long)r.getObject("waiting_id"),
                (Integer)r.getObject("call_count"),r.getString("connection_generation"),r.getString("event_type"),r.getString("message"));
    }
    private static AttemptRow attempt(ResultSet r) throws SQLException {
        return new AttemptRow(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("status"));
    }
}
