-- Audited, read-only database operator only. Global capacity is not exposed via customer/owner APIs.
-- No message bodies or personal data. No mutations or implied replay operations.
SELECT retained_roots,(SELECT count(*) FROM waiting_sse_root_operator) AS actual_retained_roots
FROM waiting_sse_capacity WHERE id=1;

SELECT status,count(*) AS roots,
       max(clock_timestamp()-created_at) FILTER (WHERE status IN ('PENDING','CLAIMED')) AS oldest_pending_age
FROM waiting_sse_root_operator GROUP BY status;

SELECT status,reason,count(*) AS targets FROM waiting_sse_target_operator GROUP BY status,reason ORDER BY status,reason;

SELECT r.id,r.event_key,r.expected_recipients,r.expected_targets,count(t.id) AS actual_targets,r.expanded_at
FROM waiting_sse_root_operator r LEFT JOIN waiting_sse_target_operator t ON t.root_id=r.id
GROUP BY r.id,r.event_key,r.expected_recipients,r.expected_targets,r.expanded_at,r.created_at HAVING (r.expanded_at IS NOT NULL AND count(t.id)<>r.expected_targets)
   OR (r.expanded_at IS NULL AND count(t.id)>0)
ORDER BY r.created_at LIMIT 500;

SELECT phase,reason,last_confirmed_stage,count(*) FROM waiting_sse_observation
WHERE phase IN ('UNKNOWN','EXPANSION_BLOCKED') GROUP BY phase,reason,last_confirmed_stage;
