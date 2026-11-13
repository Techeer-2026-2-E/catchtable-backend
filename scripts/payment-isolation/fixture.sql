-- Run only in dedicated ct_improve_d/d_probe schema AFTER Flyway, before app starts.
INSERT INTO member(id,email,password,name,phone,user_type)
OVERRIDING SYSTEM VALUE VALUES (1,'probe-customer@example.invalid','local','probe','010-0000-0000','CUSTOMER'),
       (2,'probe-owner@example.invalid','local','probe','010-0000-0000','OWNER');
INSERT INTO store(id,owner_id,name,category,address,waiting_available,reservation_duration_minutes,reservation_slot_minutes,arrival_grace_minutes)
OVERRIDING SYSTEM VALUE VALUES (1,2,'isolation probe','KOREAN','synthetic',true,60,30,10);
INSERT INTO store_table(id,store_id,table_number,capacity,status)
OVERRIDING SYSTEM VALUE SELECT n,1,n,4,'ACTIVE' FROM generate_series(1,100) n;
INSERT INTO business_hour(store_id,day_of_week,open_time,closing_time)
SELECT 1,n,'00:00','23:59' FROM generate_series(1,7) n;
INSERT INTO reservation(id,member_id,store_id,table_id,reservation_start_at,reservation_end_at,party_size,status,requested_at,hold_expires_at,deposit_amount_snapshot,refund_policy_snapshot)
OVERRIDING SYSTEM VALUE SELECT n,1,1,n,'2030-01-20 18:00+09','2030-01-20 19:00+09',2,'PENDING_PAYMENT','2030-01-20 11:59+09','2030-01-20 12:04+09',10000,'FLEXIBLE'
FROM generate_series(1,100) n;
INSERT INTO payment(id,member_id,reservation_id,order_id,order_name,amount,status,refunded_amount)
OVERRIDING SYSTEM VALUE SELECT n,1,n,'probe-order-'||n,'synthetic deposit',10000,'READY',0 FROM generate_series(1,100) n;
-- Payment 1 is used for real confirm/restart smoke, others are ongoing reconciliation targets.
UPDATE reservation SET approval_started_at='2030-01-20 11:59+09',approval_payment_id=id,
    approval_order_id='probe-order-'||id,approval_payment_key='probe-key-'||id WHERE id>1;
INSERT INTO waiting(id,member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,created_at,updated_at)
OVERRIDING SYSTEM VALUE VALUES (1,1,1,'2030-01-20',2,1,1,'WAITING','2030-01-20 11:59+09','2030-01-20 11:59+09');
SELECT setval(pg_get_serial_sequence('member','id'),2);
SELECT setval(pg_get_serial_sequence('store','id'),1);
SELECT setval(pg_get_serial_sequence('store_table','id'),100);
SELECT setval(pg_get_serial_sequence('reservation','id'),100);
SELECT setval(pg_get_serial_sequence('payment','id'),100);
SELECT setval(pg_get_serial_sequence('waiting','id'),1);
