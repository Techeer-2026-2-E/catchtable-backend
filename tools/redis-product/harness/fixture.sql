-- Only applied to a newly migrated, empty template in the private lab.
BEGIN;
INSERT INTO member(id, name, phone, user_type) OVERRIDING SYSTEM VALUE
SELECT n, 'fixture', '00000000000', CASE WHEN n <= 3 THEN 'OWNER' ELSE 'CUSTOMER' END
FROM generate_series(1, 23000) n;

INSERT INTO store(id, owner_id, name, category, address, waiting_available,
                  reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes)
OVERRIDING SYSTEM VALUE
SELECT s, s, 'fixture-' || s, 'KOREAN', 'fixture', true, 120, 30, 10 FROM generate_series(1, 3) s;

INSERT INTO store_table(id, store_id, table_number, capacity, status, min_capacity)
OVERRIDING SYSTEM VALUE
SELECT (s-1)*24+t, s, t, 4, 'ACTIVE', 1 FROM generate_series(1,3) s CROSS JOIN generate_series(1,24) t;

INSERT INTO business_hour(store_id, day_of_week, open_time, closing_time)
SELECT s, d, '00:00', '23:59' FROM generate_series(1,3) s CROSS JOIN generate_series(1,7) d;

-- 40 historical dates with one interval per physical table and date.
INSERT INTO reservation(member_id,store_id,table_id,reservation_start_at,reservation_end_at,party_size,status,requested_at,confirmed_at)
SELECT 4, s, (s-1)*24+t, (current_date-d) + time '12:00', (current_date-d) + time '14:00',
       2, 'COMPLETED', now()-interval '50 days', now()-interval '50 days'
FROM generate_series(1,3) s CROSS JOIN generate_series(1,24) t CROSS JOIN generate_series(1,40) d;

INSERT INTO waiting(member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,created_at,updated_at)
SELECT 4+t, s, current_date-d, 2, t, t, 'CANCELED', now()-d*interval '1 day', now()-d*interval '1 day'
FROM generate_series(1,3) s CROSS JOIN generate_series(1,24) t CROSS JOIN generate_series(1,40) d;

INSERT INTO reservation(member_id,store_id,table_id,reservation_start_at,reservation_end_at,party_size,status,requested_at,confirmed_at)
SELECT 4, s, (s-1)*24+t, (current_date+1) + time '12:00', (current_date+1) + time '14:00',
       2, 'CONFIRMED', now(), now()
FROM generate_series(1,3) s CROSS JOIN generate_series(1,12) t;
ANALYZE;
COMMIT;
