-- PostgreSQL prepared-statement parameters: $1 waiting_id, $2 store_id, $3 result_limit (1..500).
-- Authorized DB operators only. This query is NOT an application authorization boundary.
-- No names, phone numbers, notification bodies, or free-form cancellation reasons are selected.
WITH target AS MATERIALIZED (
    SELECT id FROM waiting WHERE id = $1 AND store_id = $2
), timeline AS (
    SELECT e.occurred_at, e.recorded_at, 'EVENT_V36'::text AS record_origin,
           e.event_type, e.source_type, e.source_id, e.call_count, e.recall_count,
           e.details, 0 AS source_rank, e.id AS sequence_id
    FROM waiting_event e JOIN target t ON t.id = e.waiting_id
    UNION ALL
    SELECT h.created_at, NULL::timestamptz, 'ORIGINAL_END_HISTORY',
           h.reason, 'WAITING_END_HISTORY', h.id::text, NULL::integer, NULL::integer,
           jsonb_build_object('previousStatus', h.previous_status, 'status', h.status,
               'previousReason', h.previous_reason, 'reason', h.reason, 'actorId', h.actor_id), 1, h.id
    FROM waiting_end_history h JOIN target t ON t.id = h.waiting_id
    WHERE NOT EXISTS (SELECT 1 FROM waiting_event e WHERE e.waiting_id = h.waiting_id
        AND e.source_type = 'WAITING_END_HISTORY' AND e.source_id = h.id::text)
    UNION ALL
    SELECT h.created_at, NULL::timestamptz, 'ORIGINAL_DEFER_HISTORY',
           'DEFERRED', 'WAITING_DEFER_HISTORY', h.id::text, NULL::integer, NULL::integer,
           jsonb_build_object('beforePosition', h.before_position, 'afterPosition', h.after_position,
               'actorId', h.actor_id), 2, h.id
    FROM waiting_defer_history h JOIN target t ON t.id = h.waiting_id
    WHERE NOT EXISTS (SELECT 1 FROM waiting_event e WHERE e.waiting_id = h.waiting_id
        AND e.source_type = 'WAITING_DEFER_HISTORY' AND e.source_id = h.id::text)
    UNION ALL
    -- A mutable V32 source snapshot, not a reconstructed send/result event.
    -- It may overlap EVENT_V36 rows; its explicit origin distinguishes it.
    SELECT o.updated_at, NULL::timestamptz, 'CURRENT_OUTBOX_SNAPSHOT',
           o.status, 'ALIMTALK_OUTBOX', o.id::text, o.call_count, o.recall_count,
           jsonb_build_object('status', o.status, 'attemptCount', o.attempt_count,
               'transportMode', o.transport_mode, 'errorCode', o.last_error_code,
               'createdAt', o.created_at, 'expiresAt', o.expires_at,
               'providerMessageIdKnown', o.provider_message_id IS NOT NULL,
               'customerReceipt', 'UNKNOWN'), 3, 0::bigint
    FROM alimtalk_outbox o JOIN target t ON t.id = o.waiting_id
)
SELECT occurred_at, recorded_at, record_origin, event_type, source_type, source_id,
       call_count, recall_count, details, source_rank, sequence_id
FROM timeline
ORDER BY occurred_at, source_rank, sequence_id, source_id
LIMIT LEAST(GREATEST(COALESCE($3, 100), 1), 500);
