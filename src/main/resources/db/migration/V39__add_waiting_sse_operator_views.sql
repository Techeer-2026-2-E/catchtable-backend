-- Owner-rights projections: operators can read correlation data without SELECT on raw message columns.
-- Keep the views owned by the trusted migration/table owner; do not grant operators ownership,
-- membership in that role, schema CREATE, base-table message access, or write privileges.
-- Role provisioning is deliberately outside Flyway. No existing data/checksum is rewritten.
CREATE VIEW waiting_sse_root_operator WITH (security_barrier = true) AS
SELECT id,event_key,kind,waiting_id,call_count,recall_count,store_id,status,created_at,
       lease_until,expanded_at,expected_recipients,expected_targets
FROM waiting_sse_root;

CREATE VIEW waiting_sse_target_operator WITH (security_barrier = true) AS
SELECT id,root_id,waiting_id,event_type,message->>'eventId' AS sse_event_id,
       connection_generation,status,reason
FROM waiting_sse_target;

REVOKE ALL ON waiting_sse_root_operator,waiting_sse_target_operator FROM PUBLIC;
