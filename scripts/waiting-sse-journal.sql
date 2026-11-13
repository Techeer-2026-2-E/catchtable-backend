-- Restricted operator diagnostic, not a public API or an authorization mechanism.
-- Execute only in an audited read-only operator session authorized for $2 store.
-- $1 = waiting_id, $2 = store_id, $3 = requested row limit (1..500).
-- Requires V39 owner-rights operator views and the column grants documented in docs/waiting-sse-durability.md.
-- No SELECT on either raw message column is required or authorized.
WITH authorized_waiting AS (
    SELECT id FROM waiting WHERE id=$1 AND store_id=$2
), roots AS (
    SELECT DISTINCT r.id,r.event_key,r.kind,r.waiting_id,r.call_count,r.recall_count,r.store_id,
           r.status,r.created_at,r.lease_until,r.expanded_at,r.expected_recipients,r.expected_targets
    FROM waiting_sse_root_operator r
    LEFT JOIN waiting_sse_target_operator t ON t.root_id=r.id
    WHERE r.store_id=$2 AND (r.waiting_id IN (SELECT id FROM authorized_waiting)
        OR t.waiting_id IN (SELECT id FROM authorized_waiting))
)
SELECT r.id AS root_id,r.event_key,r.kind,r.waiting_id AS cause_waiting_id,r.call_count,r.recall_count,
       r.status AS root_status,r.created_at,r.lease_until,r.expanded_at,r.expected_recipients,r.expected_targets,
       (SELECT count(*) FROM waiting_sse_target_operator actual WHERE actual.root_id=r.id) AS actual_targets,
       t.id AS target_id,t.waiting_id AS target_waiting_id,t.event_type,t.sse_event_id,t.connection_generation,t.status AS target_status,t.reason,
       a.id AS attempt_id,a.status AS attempt_status,a.marked_at,a.observed_at,a.customer_receipt,
       o.id AS observation_id,o.phase,o.reason AS observation_reason,o.last_confirmed_stage,o.recorded_at,
       e.id AS business_event_id,e.event_type AS business_event_type
FROM roots r
LEFT JOIN waiting_sse_target_operator t ON t.root_id=r.id
LEFT JOIN waiting_sse_attempt a ON a.target_id=t.id
LEFT JOIN waiting_sse_observation o ON o.root_id=r.id AND (o.target_id=t.id OR o.target_id IS NULL)
LEFT JOIN waiting_event e ON e.event_key=split_part(split_part(r.event_key,':direct:',1),':queue:',1)
ORDER BY r.created_at,r.id,t.id,o.id
LIMIT greatest(1,least(coalesce($3,100),500));
