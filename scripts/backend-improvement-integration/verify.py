#!/usr/bin/env python3
"""Historical fixed V37 probes; current V39 uses verify_sse.py instead."""
import argparse, hashlib, importlib.util, json, os, re, subprocess, sys, time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--mode',choices=['upgrade','restart'],required=True);p.add_argument('--output',required=True);p.add_argument('--schema');p.add_argument('--check-only',action='store_true')
args=p.parse_args()
# Before mkdir, probe import, build, or DB access: accept only the preserved V37 main325.
# Canonical src/main path/SHA map from final-reintegration-20261111/integration-delta/source-sha256.json.
main_files = {}
if any(entry.is_symlink() for entry in (ROOT/'src',ROOT/'src/main')):
    p.exit(2, 'Historical V37 preflight FAILED: symlink source root\n')
for entry in sorted((ROOT/'src/main').rglob('*')):
    if entry.is_symlink() or not (entry.is_file() or entry.is_dir()):
        p.exit(2, 'Historical V37 preflight FAILED: non-regular source input\n')
    if entry.is_file():
        main_files[entry.relative_to(ROOT).as_posix()] = hashlib.sha256(entry.read_bytes()).hexdigest()
main_hash = hashlib.sha256(json.dumps(main_files,sort_keys=True,separators=(',',':')).encode()).hexdigest()
if len(main_files)!=325 or main_hash!='3153d3edebb7b4cf3f2049d4ef7b0d598fc01eb3299d94a82e3930ac4ba5ef36':
    p.exit(2, 'Historical V37 input required: src/main325 fingerprint mismatch; current V39 must use verify_sse.py (see README). No output/DB changes made.\n')
if args.check_only:
    print(json.dumps({'status':'VALID_HISTORICAL_V37_INPUT','main_files':len(main_files),'runtime_or_database_executed':False}))
    raise SystemExit(0)
out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=False)
schema=args.schema or ('i_upgrade_v35' if args.mode=='upgrade' else 'i_restart')
assert re.fullmatch(r'i_'+args.mode+r'_[a-z0-9_]+',schema) or schema=='i_restart','integration-owned schema required'
config={'PAYMENT_PROBE_CONTAINER':'ct-improve-i-20261111','PAYMENT_PROBE_DB_PORT':'55435',
        'PAYMENT_PROBE_DB_NAME':'ct_improve_i','PAYMENT_PROBE_DB_USER':'catchtable',
        'PAYMENT_PROBE_DB_PASSWORD':'ct_improve_i_local_only','PAYMENT_PROBE_SCHEMA':schema,
        'PAYMENT_PROBE_APP_PORT':'18085','PAYMENT_PROBE_PG_PORT':'19085'}
os.environ.update(config)
spec=importlib.util.spec_from_file_location('payment_probe',ROOT/'scripts/payment-isolation/run.py')
probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
original_sql=probe.sql

def sql(query):
    with (out/'executed.sql').open('a') as log:log.write(query+';\n')
    return original_sql(query)
probe.sql=sql

def save(name,data):
    (out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2))

def rows(table):
    return json.loads(sql(f"SELECT coalesce(json_agg(t ORDER BY row_to_json(t)::text),'[]'::json) FROM {table} t"))

def dump(name):
    with (out/name).open('wb') as f:
        subprocess.run(['docker','exec',probe.CONTAINER,'pg_dump','-U',probe.DB_USER,'-d',probe.DB_NAME,'-n',schema,'--no-owner','--no-acl'],stdout=f,check=True)

# Verify the container identity/loopback binding before using or resetting any probe schema.
info=json.loads(subprocess.check_output(['docker','inspect',probe.CONTAINER],text=True))[0]
assert info['Config']['Labels'].get('catchtable.task')=='task_19618ae1c3f9'
assert info['NetworkSettings']['Ports']['5432/tcp']==[{'HostIp':'127.0.0.1','HostPort':'55435'}]
assert info['Image'].startswith('sha256:1a6ab3f5345e')
assert sql(f"SELECT count(*) FROM information_schema.schemata WHERE schema_name='{schema}'")=='0','preserve existing probe schema; choose a new reviewed name'
probe.check_ports()
save('environment.json',{'mode':args.mode,'source':str(ROOT),'config':config,'argv':sys.argv,'java':str(probe.JAVA),'started_at':time.time()})
probe.build(ROOT,out)

if args.mode=='upgrade':
    sql(f'CREATE SCHEMA {schema}')
    options=['--payment.jobs.enabled=false','--reservation.refund-outbox.scheduling-enabled=false','--toss.secret-key=',
             '--reservation.hold-expiry-interval-ms=3600000','--waiting.expiry-interval-ms=3600000']
    app=probe.start(ROOT,out,'v35-start',True,[*options,'--spring.flyway.target=35'])
    probe.stop(app)
    before_history=rows('flyway_schema_history')
    assert len(before_history)==34 and max(int(r['version']) for r in before_history)==35
    sql((ROOT/'scripts/payment-isolation/fixture.sql').read_text())
    sql("""
    UPDATE waiting SET status='NO_SHOW',call_count=3,recall_count=2 WHERE id=1;
    INSERT INTO waiting_end_history(waiting_id,previous_status,status,reason,detail,actor_id,created_at)
      VALUES(1,'CALLED','NO_SHOW','NO_RESPONSE','synthetic legacy',2,'2026-11-10 12:05+09');
    INSERT INTO waiting_defer_history(waiting_id,before_position,after_position,actor_id,created_at)
      VALUES(1,1,2,2,'2026-11-10 12:00+09');
    INSERT INTO alimtalk_outbox(id,waiting_id,member_id,call_count,recall_count,transport_mode,recipient,store_name,wait_number,expires_at,status,attempt_count,next_attempt_at,created_at,updated_at)
      VALUES('00000000-0000-0000-0000-000000000001',1,1,3,2,'simulation','01000000000','synthetic',1,'2030-01-20 12:05+09','UNKNOWN',1,'2030-01-20 12:00+09','2026-11-10 12:01+09','2026-11-10 12:02+09');
    UPDATE payment SET status='PARTIAL_REFUNDED',payment_key='legacy-synthetic',approved_at='2030-01-20 11:59+09',refunded_amount=3000 WHERE id=1;
    INSERT INTO payment_status_history(payment_id,previous_status,status,source,detail,created_at)
      VALUES(1,'DONE','PARTIAL_REFUNDED','TOSS_CANCEL','synthetic prior refund','2030-01-20 11:59+09');
    INSERT INTO payment_refund(payment_id,idempotency_key,requested_amount,cancel_reason,refund_amount,status,total_refunded_amount,requested_at,completed_at)
      VALUES(1,'legacy-refund',3000,'synthetic',3000,'COMPLETED',3000,'2030-01-20 11:58+09','2030-01-20 11:59+09');
    INSERT INTO reservation_refund_request(reservation_id,payment_id,idempotency_key,intent,cancel_reason,requested_amount,requested_at,reservation_start_at,policy_refund_limit,refund_amount,refunded_amount_at_request,status,total_refunded_amount,completed_at)
      VALUES(1,1,'legacy-refund','UNCONFIRMED_REFUND','synthetic',3000,'2030-01-20 11:58+09','2030-01-20 18:00+09',10000,3000,0,'COMPLETED',3000,'2030-01-20 11:59+09');
    INSERT INTO reservation_refund_outbox(request_id,status,attempt_count,next_attempt_at,created_at,updated_at,delivered_at)
      VALUES(1,'DELIVERED',1,'2030-01-20 11:58+09','2030-01-20 11:58+09','2030-01-20 11:59+09','2030-01-20 11:59+09');
    """)
    tables=sql(f"SELECT tablename FROM pg_tables WHERE schemaname='{schema}' AND tablename<>'flyway_schema_history' ORDER BY tablename").splitlines()
    before={t:rows(t) for t in tables};save('before.json',before);save('history-before.json',before_history);dump('v35-before.sql')
    app=probe.start(ROOT,out,'v37-upgrade',True,options);probe.stop(app)
    after={t:rows(t) for t in tables};save('after.json',after)
    after_history=rows('flyway_schema_history');save('history-after.json',after_history)
    assert before==after,'legacy data changed'
    # Ignore ordering by text in rows(), compare the complete original history rows by rank.
    assert sorted(before_history,key=lambda x:x['installed_rank'])==sorted([r for r in after_history if int(r['version'])<=35],key=lambda x:x['installed_rank'])
    assert len(after_history)==36 and {r['version'] for r in after_history if int(r['version'])>35}=={'36','37'}
    assert sql('SELECT count(*) FROM waiting_event')=='0'
    assert sql('SELECT count(*) FROM payment_reconcile_job')=='0'
    # Restart explicitly validates existing V37 checksums and JPA mapping a second time.
    app=probe.start(ROOT,out,'v37-validate',True,options);probe.stop(app)
    dump('v37-after.sql')
    save('result.json',{'outcome':'passed','old_sql_history_rows':34,'final_history_rows':36,'preserved_tables':len(tables),
         'preserved_rows':{t:len(v) for t,v in before.items()},'backfilled_waiting_events':0,'preexisting_jobs':0,
         'flyway_validate':'v37-validate.log','jpa_validate':'v37-upgrade.log and v37-validate.log','schema_preserved':schema})
else:
    def observe(app,dest):
        statement="""SELECT row_to_json(t) FROM (
          SELECT w.status,w.end_reason,e.event_type,e.details->'before'->>'status' before_status,
                 e.details->'after'->>'status' after_status,e.call_count,e.source_type,
                 (e.source_id=h.id::text AND e.occurred_at=h.created_at) same_history,
                 (e.occurred_at=w.ended_at) same_state_time
          FROM waiting w JOIN waiting_end_history h ON h.waiting_id=w.id
          JOIN waiting_event e ON e.waiting_id=w.id AND e.source_type='WAITING_END_HISTORY'
          WHERE w.id=1) t"""
        result=json.loads(sql(statement))
        assert result=={'status':'NO_SHOW','end_reason':'AUTO_EXPIRED','event_type':'AUTO_EXPIRED',
                        'before_status':'CALLED','after_status':'NO_SHOW','call_count':1,'source_type':'WAITING_END_HISTORY',
                        'same_history':True,'same_state_time':True},result
        active=probe.http(probe.PG_PORT,'/__state')[1]['active'];assert active>0
        thread_dump=subprocess.check_output([str(probe.JAVA.parent/'jcmd'),str(app.pid),'Thread.print'],text=True)
        (dest/'threads-during-pg.txt').write_text(thread_dump)
        blocks=thread_dump.split('\n\n')
        worker=[b for b in blocks if b.startswith('"payment-worker-') and 'PaymentReconcileJobWorker.poll' in b]
        assert worker,'actual PG worker is not blocked on its dedicated scheduler'
        log=(dest/'app-before-crash.log').read_text()
        assert 'PROBE_COMMON_THREAD scheduling-' in log
        assert 'PROBE_SCHEDULER bean=taskScheduler pool=1 prefix=scheduling-' in log
        assert 'PROBE_SCHEDULER bean=paymentTaskScheduler pool=2 prefix=payment-worker-' in log
        assert probe.http(probe.PG_PORT,'/__state')[1]['active']>0
        save('scheduler-history.json',{'waiting':result,'pg_active':active,'pg_thread':worker[0].splitlines()[0],
              'common_thread':'scheduling-1','hold_state':sql('SELECT status FROM reservation WHERE id=101')})
        return result
    fake_log=(out/'fake.log').open('w')
    fake=subprocess.Popen([sys.executable,str(probe.HERE/'fake_pg.py'),'--port',str(probe.PG_PORT),'--ledger',str(out/'pg-ledger.json')],stdout=fake_log,stderr=subprocess.STDOUT)
    try:
        probe.until(lambda:probe.http(probe.PG_PORT,'/__state',timeout=1)[0]==200,5)
        probe.smoke(ROOT,out,pg_delay_ms=8000,app_args=['--toss.read-timeout=10s','--toss.call-timeout=11s'],observer=observe)
        save('final-rows.json',{t:rows(t) for t in ('payment','payment_refund','reservation_refund_request','payment_reconcile_job','waiting','waiting_end_history','waiting_event')})
        dump('final-schema.sql')
    finally:probe.stop(fake);fake_log.close()
print(json.dumps({'outcome':'passed','mode':args.mode,'schema':schema,'output':str(out)}))
