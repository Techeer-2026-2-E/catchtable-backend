SELECT json_build_object(
 'utc', clock_timestamp(),
 'app_sessions', (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name LIKE 'rb0-app-%'),
 'activity', (SELECT coalesce(json_agg(row_to_json(q)), '[]') FROM (
   SELECT pid, application_name, wait_event_type, wait_event, pg_blocking_pids(pid) AS blockers, left(query,2200) AS query
   FROM pg_stat_activity WHERE datname=current_database() AND application_name LIKE 'rb0-app-%' AND state='active'
 ) q),
 'hot_receipts', (SELECT pending_count FROM waiting_intake_day WHERE store_id=1 AND received_date=current_date),
 'registration_slots_held',(SELECT count(*) FROM pgrowlocks('waiting_registration_permit')),
 'hot_rows', (SELECT count(*) FROM waiting WHERE store_id=1 AND waiting_date=current_date),
 'sse_pending', (SELECT count(*) FROM waiting_sse_root WHERE status IN ('PENDING','CLAIMED'))
);
