#!/usr/bin/env python3
"""I-only final V39 correctness: isolated upgrade and PG/SSE/common-scheduler restart."""
import argparse, importlib.util, json, os, re, subprocess, sys, time, threading, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--mode',choices=['upgrade','restart'],required=True);p.add_argument('--schema',required=True);p.add_argument('--output',required=True)
a=p.parse_args();schema=a.schema;assert re.fullmatch('i_sse_'+a.mode+'[a-z0-9_]*',schema)
out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False)
os.environ.update({'PAYMENT_PROBE_CONTAINER':'ct-improve-i-20261111','PAYMENT_PROBE_DB_PORT':'55435','PAYMENT_PROBE_DB_NAME':'ct_improve_i','PAYMENT_PROBE_DB_USER':'catchtable','PAYMENT_PROBE_DB_PASSWORD':'ct_improve_i_local_only','PAYMENT_PROBE_SCHEMA':schema,'PAYMENT_PROBE_APP_PORT':'18085','PAYMENT_PROBE_PG_PORT':'19085'})
spec=importlib.util.spec_from_file_location('payment_probe',ROOT/'scripts/payment-isolation/run.py');probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
original_sql=probe.sql
def sql(q):
    with (out/'executed.sql').open('a') as f:f.write(q+';\n')
    return original_sql(q)
probe.sql=sql
def save(name,data):(out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2))
def rows(table):return json.loads(sql(f"SELECT coalesce(json_agg(t ORDER BY row_to_json(t)::text),'[]'::json) FROM {table} t"))
def dump(name):
    with (out/name).open('wb') as f:subprocess.run(['docker','exec',probe.CONTAINER,'pg_dump','-U',probe.DB_USER,'-d',probe.DB_NAME,'-n',schema,'--no-owner','--no-acl'],stdout=f,check=True)
info=json.loads(subprocess.check_output(['docker','inspect',probe.CONTAINER],text=True))[0]
assert info['Config']['Labels']['catchtable.task']=='task_19618ae1c3f9' and info['Image'].startswith('sha256:1a6ab3f5345e')
assert info['NetworkSettings']['Ports']['5432/tcp']==[{'HostIp':'127.0.0.1','HostPort':'55435'}]
assert sql(f"SELECT count(*) FROM information_schema.schemata WHERE schema_name='{schema}'")=='0'
probe.check_ports();save('environment.json',{'schema':schema,'mode':a.mode,'args':sys.argv,'started_at':time.time(),'database':'127.0.0.1:55435/ct_improve_i','app_port':18085,'fake_port':19085,'source':str(ROOT)})
probe.build(ROOT,out)
if a.mode=='upgrade':
    sql(f'CREATE SCHEMA {schema}')
    options=['--payment.jobs.enabled=false','--reservation.refund-outbox.scheduling-enabled=false','--toss.secret-key=',
      '--waiting.sse.delivery-enabled=false','--waiting.sse.scheduling-enabled=false',
      '--reservation.hold-expiry-interval-ms=3600000','--waiting.expiry-interval-ms=3600000']
    app=probe.start(ROOT,out,'v37-start',True,[*options,'--spring.flyway.target=37']);probe.stop(app)
    sql((ROOT/'scripts/payment-isolation/fixture.sql').read_text())
    # Reuse the exact synthetic legacy records of the immutable I V35->V37 test; no source data is read.
    import ast
    tree=ast.parse((ROOT/'scripts/backend-improvement-integration/verify.py').read_text())
    seeds=[n.args[0].value for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='sql' and n.args and isinstance(n.args[0],ast.Constant) and isinstance(n.args[0].value,str) and 'UPDATE waiting SET status=' in n.args[0].value]
    assert len(seeds)==1;sql(seeds[0])
    sql("""INSERT INTO waiting_event(event_key,waiting_id,event_type,occurred_at,call_count,recall_count,source_type,source_id,details)
      VALUES('synthetic-legacy-1',1,'AUTO_EXPIRED','2026-11-10 12:05+09',3,2,'WAITING_END_HISTORY','1','{"legacySynthetic":true}');
      UPDATE payment SET status='TIMEOUT',payment_key='synthetic-unknown-2' WHERE id=2;
      INSERT INTO payment_refund(payment_id,idempotency_key,requested_amount,cancel_reason,refund_amount,status,total_refunded_amount,requested_at)
      VALUES(2,'synthetic-unknown-refund',3000,'synthetic',3000,'UNKNOWN',0,'2030-01-20 11:58+09');
      INSERT INTO payment_reconcile_job(payment_id,status,requested_version,claimed_version,attempt_count,next_attempt_at,lease_until,created_at,updated_at)
      VALUES(2,'PROCESSING',1,1,1,'2030-01-20 11:58+09','2030-01-20 12:05+09','2030-01-20 11:58+09','2030-01-20 11:58+09');""")
    tables=sql(f"SELECT tablename FROM pg_tables WHERE schemaname='{schema}' AND tablename<>'flyway_schema_history' ORDER BY tablename").splitlines()
    seqnames=sql(f"SELECT sequencename FROM pg_sequences WHERE schemaname='{schema}' ORDER BY sequencename").splitlines()
    def sequences():return {s:sql(f'SELECT last_value,is_called FROM {s}') for s in seqnames}
    before={t:rows(t) for t in tables};seq=sequences();hist=rows('flyway_schema_history');assert len(hist)==36
    save('before.json',before);save('sequences-before.json',seq);save('history-before.json',hist);dump('v37-before.sql')
    app=probe.start(ROOT,out,'v39-upgrade',True,options);probe.stop(app)
    after={t:rows(t) for t in tables};afterseq=sequences();afterhist=rows('flyway_schema_history')
    save('after.json',after);save('sequences-after.json',afterseq);save('history-after.json',afterhist)
    assert before==after and seq==afterseq
    assert hist==[r for r in afterhist if int(r['version'])<=37]
    assert len(afterhist)==38 and all(r['success'] for r in afterhist)
    for t in ['waiting_sse_root','waiting_sse_target','waiting_sse_attempt','waiting_sse_observation']:assert sql(f'SELECT count(*) FROM {t}')=='0'
    assert sql('SELECT retained_roots FROM waiting_sse_capacity')=='0'
    app=probe.start(ROOT,out,'v39-validate',True,options);probe.stop(app);dump('v39-after.sql')
    save('result.json',{'outcome':'passed','preserved_tables':len(tables),'preserved_sequences':len(seq),'preserved_history':36,'history_after':38,'backfilled_roots_targets_attempts_observations':0,'unknown_payment':'TIMEOUT','unknown_refund':'UNKNOWN','data_equal':True,'sequence_equal':True})
else:
    class Stream:
        def __init__(self,name):
            self.path=out/(name+'.jsonl');self.frames=[];self.error=None;self.response=None
            self.thread=threading.Thread(target=self.read,daemon=True);self.thread.start()
        def read(self):
            try:
                self.response=urllib.request.urlopen(f'http://127.0.0.1:{probe.APP_PORT}/api/user/notifications/subscribe?memberId=1',timeout=30)
                fields={}
                for raw in self.response:
                    line=raw.decode().rstrip('\r\n')
                    if not line:
                        if fields:
                            row={'at_ns':time.monotonic_ns(),**fields};self.frames.append(row)
                            with self.path.open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
                        fields={}
                    elif ':' in line:
                        k,v=line.split(':',1);fields[k]=v.lstrip()
            except Exception as e:self.error=repr(e)
        def seen(self,event):return [f for f in self.frames if f.get('event')==event]
    streams=[];state={}
    def started(app,dest):
        stream=Stream('wire-before-kill');streams.append(stream);probe.until(lambda:stream.seen('connect'),3)
    def snapshots():return {t:rows(t) for t in ['waiting','waiting_end_history','waiting_event','waiting_sse_root','waiting_sse_target','waiting_sse_attempt','waiting_sse_observation']}
    def observer(app,dest):
        stream=streams[0]
        probe.until(lambda:stream.seen('WAITING_EXPIRED'),3)
        probe.until(lambda:sql("SELECT count(*) FROM waiting_sse_root WHERE waiting_id=1 AND status<>'DONE'")=='0',3)
        first=snapshots();save('sse-expiry-before-kill.json',first)
        event=[x for x in first['waiting_event'] if x['event_type']=='AUTO_EXPIRED'][0]
        end=first['waiting_end_history'][0];waiting=first['waiting'][0]
        assert event['source_id']==str(end['id']) and event['occurred_at']==end['created_at']==waiting['ended_at']
        assert waiting['status']=='NO_SHOW' and event['details']['before']['status']=='CALLED' and event['details']['after']['status']=='NO_SHOW'
        roots=first['waiting_sse_root'];assert len(roots)==3 and all(r['event_key'].startswith(event['event_key']+':') for r in roots)
        sent=[t for t in first['waiting_sse_target'] if t['status']=='SENT'];assert len(sent)==1
        attempt=first['waiting_sse_attempt'];assert len(attempt)==1 and attempt[0]['target_id']==sent[0]['id'] and attempt[0]['status']=='SEND_RETURNED' and attempt[0]['customer_receipt']=='UNKNOWN'
        wire=stream.seen('WAITING_EXPIRED');assert len(wire)==1 and json.loads(wire[0]['data'])['eventId']==sent[0]['message']['eventId']
        assert any(t['status']=='SKIPPED' and t['reason']=='NO_CONNECTION' for t in first['waiting_sse_target'])
        assert any(r['kind']=='QUEUE' and r['expected_recipients']==0 and r['expected_targets']==0 for r in roots)
        # A second legitimate call sends once, then the test wrapper holds after wire return,
        # before result persistence. Both this SSE and the pinned PG request meet the owned kill.
        sql("INSERT INTO waiting(id,member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,created_at,updated_at) OVERRIDING SYSTEM VALUE VALUES(2,1,1,'2030-01-20',2,2,2,'WAITING','2030-01-20 11:59+09','2030-01-20 11:59+09')")
        (out/'arm-after-send').touch(exist_ok=False)
        code,result=probe.http(probe.APP_PORT,'/api/owner/waitings/2/call',{}, {'X-Member-Id':'2'});assert code==200,(code,result)
        probe.until(lambda:(out/'after-send-entered').exists(),3);probe.until(lambda:stream.seen('WAITING_CALLED'),2)
        held=snapshots();save('sse-held-before-kill.json',held)
        root=[r for r in held['waiting_sse_root'] if r['waiting_id']==2 and r['event_type']=='WAITING_CALLED'][0]
        target=[t for t in held['waiting_sse_target'] if t['root_id']==root['id']][0]
        mark=[x for x in held['waiting_sse_attempt'] if x['target_id']==target['id']][0]
        assert root['status']=='CLAIMED' and target['status']==mark['status']=='MARKED'
        assert len(stream.seen('WAITING_CALLED'))==1 and json.loads(stream.seen('WAITING_CALLED')[0]['data'])['eventId']==target['message']['eventId']
        state.update(root=root['id'],target=target['id'],attempt=mark['id'],event_id=target['message']['eventId'])
        locks=sql(f"BEGIN; SELECT id FROM waiting WHERE id=2 FOR UPDATE NOWAIT; SELECT id FROM waiting_sse_root WHERE id='{root['id']}' FOR UPDATE NOWAIT; SELECT id FROM waiting_sse_target WHERE id='{target['id']}' FOR UPDATE NOWAIT; SELECT id FROM waiting_sse_attempt WHERE id='{mark['id']}' FOR UPDATE NOWAIT; ROLLBACK;")
        thread=subprocess.check_output([str(probe.JAVA.parent/'jcmd'),str(app.pid),'Thread.print'],text=True);(out/'threads-during-pg-sse.txt').write_text(thread)
        blocks=thread.split('\n\n');assert any(b.startswith('"payment-worker-') and 'PaymentReconcileJobWorker.poll' in b for b in blocks)
        assert any(b.startswith('"waiting-sse-') and 'IntegrationSseProbe$WrappedEmitter.send' in b and 'WaitingSseWorker.send' in b for b in blocks)
        log=(out/'app-before-crash.log').read_text()
        for snippet in ['PROBE_COMMON_THREAD scheduling-','bean=taskScheduler pool=1 prefix=scheduling-','bean=paymentTaskScheduler pool=2 prefix=payment-worker-','bean=waitingSseScheduler pool=1 prefix=waiting-sse-']:assert snippet in log,snippet
        boundaries=[json.loads(l) for l in (out/'boundaries.jsonl').read_text().splitlines()]
        sse=[b for b in boundaries if b['phase'].startswith('sse.')];pg=[b for b in boundaries if b['phase'].startswith('pg.enter.get') and b['thread'].startswith('payment-worker-')]
        assert sse and pg and all(not b['transaction'] and b['connectionLeases']==0 for b in sse+pg)
        save('scheduler-sse-boundaries.json',{'sse':sse,'pg':pg,'separate_connection_locks':locks,'held':state,'history_same_state_time':True})
        return {'history_same_state_time':True,'expiry_wire_sends':1,'held_wire_sends':1,'held_attempt_before_kill':'MARKED','common_thread':'scheduling-1','pg_thread':'payment-worker-1','sse_thread':(out/'after-send-entered').read_text()}
    def killed(dest):
        assert sql(f"SELECT status FROM waiting_sse_attempt WHERE id='{state['attempt']}'")=='MARKED'
        sql(f"UPDATE waiting_sse_root SET lease_until=clock_timestamp()-interval '1 second' WHERE id='{state['root']}'")
        # Root lease uses DB wall time, unlike the payment probe's synthetic Clock.
        save('after-kill.json',{'held':state,'lease_acceleration':'SQL DB clock_timestamp minus 1 second; no claim about real 30 second wait'})
    def restarted(app,dest):
        stream=Stream('wire-after-restart');streams.append(stream);probe.until(lambda:stream.seen('connect'),3)
        probe.until(lambda:sql(f"SELECT status FROM waiting_sse_root WHERE id='{state['root']}'")=='UNKNOWN',5)
        assert sql(f"SELECT status FROM waiting_sse_attempt WHERE id='{state['attempt']}'")=='UNKNOWN'
        assert sql(f"SELECT count(*) FROM waiting_sse_attempt WHERE target_id='{state['target']}'")=='1'
        assert sql(f"SELECT count(*) FROM waiting_sse_observation WHERE attempt_id='{state['attempt']}' AND phase='UNKNOWN'")=='1'
        save('sse-after-restart.json',snapshots())
    fake_log=(out/'fake.log').open('w');fake=subprocess.Popen([sys.executable,str(probe.HERE/'fake_pg.py'),'--port','19085','--ledger',str(out/'pg-ledger.json')],stdout=fake_log,stderr=subprocess.STDOUT)
    try:
        probe.until(lambda:probe.http(19085,'/__state',timeout=1)[0]==200,5)
        options=['--toss.read-timeout=10s','--toss.call-timeout=11s','--waiting.sse.delivery-enabled=true','--waiting.sse.scheduling-enabled=true','--waiting.sse.max-roots-per-poll=64','--waiting.sse.max-run-millis=250','--probe.sse.enabled=true','--probe.sse.directory='+str(out)]
        probe.smoke(ROOT,out,pg_delay_ms=8000,app_args=options,observer=observer,on_started=started,after_kill=killed,on_restarted=restarted)
        assert not streams[1].seen('WAITING_CALLED')
        final=snapshots();save('sse-final.json',final)
        assert [x for x in final['waiting_sse_attempt'] if x['id']==state['attempt']][0]['status']=='UNKNOWN'
        assert len([x for x in final['waiting_sse_attempt'] if x['target_id']==state['target']])==1
        save('final-rows.json',{t:rows(t) for t in ['payment','payment_refund','reservation','reservation_refund_request','payment_reconcile_job']})
        save('result.json',{'outcome':'passed','real_expiry_send':1,'real_precrash_call_send':1,'reconnected_call_replay':0,'sse_recovered_unknown':state,'customer_receipt':'UNKNOWN','send_connection_leases':0,'send_transaction':False,'max_roots_per_poll':64,'max_run_millis':250})
        dump('final-schema.sql')
    finally:
        probe.stop(fake);fake_log.close()
        for stream in streams:stream.thread.join(timeout=1)
print(json.dumps({'outcome':'passed','mode':a.mode,'schema':schema,'output':str(out)}))
