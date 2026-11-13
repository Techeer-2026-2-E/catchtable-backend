package com.catchtable.notification.durability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Test-only, fixture-scoped drain. Unexpected CLAIMED/UNKNOWN and exhausted budgets are failures. */
public final class WaitingSseTestDrain {
    private final JdbcTemplate jdbc;
    private final WaitingSseWorker worker;
    private final Supplier<Set<UUID>> scope;

    private WaitingSseTestDrain(JdbcTemplate jdbc,WaitingSseWorker worker,Supplier<Set<UUID>> scope) {
        this.jdbc=jdbc;this.worker=worker;this.scope=scope;
    }
    public static WaitingSseTestDrain forStore(JdbcTemplate jdbc,WaitingSseWorker worker,Long storeId) {
        return new WaitingSseTestDrain(jdbc,worker,()->Set.copyOf(jdbc.queryForList("SELECT id FROM waiting_sse_root WHERE store_id=?",UUID.class,storeId)));
    }
    public static WaitingSseTestDrain forRoots(JdbcTemplate jdbc,WaitingSseWorker worker,Set<UUID> roots) {
        var fixed=Set.copyOf(roots);
        return new WaitingSseTestDrain(jdbc,worker,()->fixed);
    }
    public void done() { expecting(Map.of()); }
    public void expecting(Map<UUID,String> residualStates) { expecting(residualStates,1000); }
    public void expecting(Map<UUID,String> residualStates,int maximumRuns) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("Drain must run after the business TX; use assertStates inside it").isFalse();
        Set<UUID> roots=scope.get();
        assertThat(roots).as("Expected residual roots must belong to this fixture").containsAll(residualStates.keySet());
        Map<UUID,String> expected=new LinkedHashMap<>();roots.forEach(id->expected.put(id,residualStates.getOrDefault(id,"DONE")));
        for(int runs=0;runs<maximumRuns;runs++) {
            Map<UUID,String> current=states(roots);
            if (current.equals(expected)) break;
            worker.runOnce();
            // No time-based lease waiting or silent early success when a worker leaves only CLAIMED.
            if (states(roots).equals(current) && current.values().stream().noneMatch("PENDING"::equals)) break;
        }
        assertStates(expected);
    }
    /** Also usable inside a rollback/pre-commit assertion; it never invokes the worker. */
    public void assertStates(Map<UUID,String> expected) {
        assertThat(states(scope.get())).as("Every scoped root must reach its declared state (including CLAIMED/UNKNOWN)").isEqualTo(expected);
        for(var entry:expected.entrySet()) {
            if (!entry.getValue().equals("DONE")) continue;
            UUID root=entry.getKey();
            assertThat(jdbc.queryForObject("SELECT expanded_at IS NOT NULL AND expected_targets=(SELECT count(*) FROM waiting_sse_target WHERE root_id=?) FROM waiting_sse_root WHERE id=?",Boolean.class,root,root))
                    .as("DONE root %s must have complete expansion",root).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM waiting_sse_target WHERE root_id=? AND status IN ('READY','MARKED','UNKNOWN')",Long.class,root))
                    .as("DONE root %s cannot hide unfinished/unknown targets",root).isZero();
        }
    }
    private Map<UUID,String> states(Set<UUID> roots) {
        Map<UUID,String> states=new LinkedHashMap<>();
        for(UUID id:roots) {
            var rows=jdbc.queryForList("SELECT status FROM waiting_sse_root WHERE id=?",String.class,id);
            assertThat(rows).as("Root %s must not disappear",id).hasSize(1);states.put(id,rows.getFirst());
        }
        return states;
    }
}
