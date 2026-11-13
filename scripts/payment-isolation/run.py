#!/usr/bin/env python3
"""Dedicated-port local correctness smoke and deferred before/after load runner."""
import argparse, concurrent.futures, json, math, os, re, shutil, socket, subprocess, sys, threading, time, urllib.request, urllib.error
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
BASE='a44952e37993e5003c860714f126057cd311dba5'
# Explicit overrides support a separate integration DB without touching the D source DB.
CONTAINER=os.environ.get('PAYMENT_PROBE_CONTAINER','ct-improve-d-20261111')
DB_PORT=int(os.environ.get('PAYMENT_PROBE_DB_PORT','55434'))
DB_NAME=os.environ.get('PAYMENT_PROBE_DB_NAME','ct_improve_d')
DB_USER=os.environ.get('PAYMENT_PROBE_DB_USER','ct_improve_d')
SCHEMA=os.environ.get('PAYMENT_PROBE_SCHEMA','d_probe')
APP_PORT=int(os.environ.get('PAYMENT_PROBE_APP_PORT','18084'))
PG_PORT=int(os.environ.get('PAYMENT_PROBE_PG_PORT','19084'))
if not all(re.fullmatch(r'[a-z][a-z0-9_]*',v) for v in (DB_NAME,DB_USER,SCHEMA)) or SCHEMA=='public':
    raise SystemExit('probe requires simple SQL identifiers and a dedicated non-public schema')
ENV={**os.environ,'DB_HOST':'127.0.0.1','DB_PORT':str(DB_PORT),'DB_NAME':DB_NAME,'DB_USERNAME':DB_USER,
     'DB_PASSWORD':os.environ.get('PAYMENT_PROBE_DB_PASSWORD','ct_improve_d_local_only'),
     'TOSS_SECRET_KEY':'local_fake_only','TOSS_BASE_URL':f'http://127.0.0.1:{PG_PORT}',
     'ALIMTALK_ENABLED':'false','ALIMTALK_MODE':'simulation','SOLAPI_BASE_URL':f'http://127.0.0.1:{PG_PORT}',
     'SOLAPI_API_KEY':'','SOLAPI_API_SECRET':'','SOLAPI_CHANNEL_ID':'','SOLAPI_TEMPLATE_ID':'','SOLAPI_WEBHOOK_SECRET':''}
JAVA=Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())/'bin/java'

def sql(query):
    result=subprocess.run(['docker','exec','-i',CONTAINER,'psql','-U',DB_USER,'-d',DB_NAME,'-XAt','-v','ON_ERROR_STOP=1'],
        input=f'SET search_path={SCHEMA},public;\n'+query,text=True,capture_output=True)
    if result.returncode: raise RuntimeError(result.stderr)
    out=result.stdout
    return '\n'.join(x for x in out.strip().splitlines() if x!='SET')

def http(port,path,body=None,headers=None,timeout=40):
    req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,
        data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**(headers or {})})
    try:
        with urllib.request.urlopen(req,timeout=timeout) as r: return r.status,json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e: return e.code,json.loads(e.read() or b'null')

def until(check,seconds=40):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=check()
            if value:return value
        except (ConnectionError,OSError):pass
        time.sleep(.1)
    raise AssertionError('condition did not complete')

def check_ports():
    for port in (APP_PORT,PG_PORT):
        with socket.socket() as s:
            if s.connect_ex(('127.0.0.1',port))==0: raise SystemExit(f'port {port} already owned; stop and ask coordinator')

def build(source,out):
    with (out/('build-'+source.name+'.log')).open('w') as log:
        subprocess.run(['./gradlew','-I','scripts/payment-isolation/probe.gradle','probeClasspath','--offline','--no-daemon','--max-workers=2','-Dorg.gradle.java.installations.auto-download=false'],cwd=source,env=ENV,stdout=log,stderr=subprocess.STDOUT,check=True)

def start(source,out,label,smoke=False,extra_args=()):
    log=(out/(label+'.log')).open('w')
    args=[str(JAVA),'-Xms256m','-Xmx512m','-cp',(source/'build/probe-classpath.txt').read_text(),
          'com.catchtable.payment.PaymentIsolationProbe',f'--server.port={APP_PORT}',
          f'--spring.datasource.url=jdbc:postgresql://127.0.0.1:{DB_PORT}/{DB_NAME}?currentSchema={SCHEMA},public',
          '--spring.datasource.hikari.maximum-pool-size=10',f'--spring.flyway.default-schema={SCHEMA}',f'--spring.flyway.schemas={SCHEMA}',
          '--server.tomcat.threads.max=40','--server.tomcat.threads.min-spare=4','--server.tomcat.mbeanregistry.enabled=true',
          '--management.endpoints.web.exposure.include=health,info,metrics','--logging.level.com.catchtable=INFO',
          '--payment.jobs.enabled=true','--payment.jobs.poll-interval-ms=100','--payment.jobs.initial-delay-ms=100',
          '--payment.reconcile-interval-ms='+('3600000' if smoke else '1000'),
          '--reservation.refund-outbox.scheduling-enabled=true',
          '--reservation.hold-expiry-interval-ms=100','--waiting.expiry-interval-ms=100',
          '--toss.connect-timeout=500ms','--toss.read-timeout=3s','--toss.call-timeout=3500ms','--toss.max-concurrent-calls=8']
    # Spring combines duplicate command-line properties with commas; replace defaults instead.
    overridden={arg.split('=',1)[0] for arg in extra_args}
    args=[arg for arg in args if arg.split('=',1)[0] not in overridden]
    args.extend(extra_args)
    (out/(label+'.command.json')).write_text(json.dumps(args,indent=2))
    process=subprocess.Popen(args,cwd=source,env=ENV,stdout=log,stderr=subprocess.STDOUT)
    process.probe_log=log
    def ready():
        if process.poll() is not None:
            raise RuntimeError(f'app {label} exited with {process.returncode}; see {label}.log')
        return http(APP_PORT,'/actuator/health',timeout=2)[0]==200
    try:
        until(ready)
    except Exception:
        stop(process); raise
    return process

def stop(process,crash=False):
    if process is not None:
        if process.poll() is None:
            process.kill() if crash else process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        if hasattr(process,'probe_log'):process.probe_log.close()

def fixture(source,out):
    sql(f'DROP SCHEMA IF EXISTS {SCHEMA} CASCADE; CREATE SCHEMA {SCHEMA};')
    app=start(source,out,'migrate',True); stop(app)
    sql((HERE/'fixture.sql').read_text())
    seed={f'probe-key-{n}':{'paymentKey':f'probe-key-{n}','orderId':f'probe-order-{n}',
           'status':'IN_PROGRESS','totalAmount':10000,'balanceAmount':10000,'method':'카드',
           'approvedAt':'2030-01-20T12:00:00+09:00','keys':[]} for n in range(1,101)}
    http(PG_PORT,'/__control',{'seed':seed,'delay_ms':0,'error':0,'lose':False,'reset_metrics':True})

def smoke(source,out,pg_delay_ms=2000,app_args=(),observer=None):
    fixture(source,out); app=None
    try:
        app=start(source,out,'app-before-crash',True,app_args)
        for path in ('/api/user/stores?size=10','/api/user/reservations/1','/api/user/waitings/1'):
            assert http(APP_PORT,path,headers={'X-Member-Id':'1'})[0]==200,path
        probe={n:metric(n) for n in ('tomcat.threads.busy','hikaricp.connections.active','probe.scheduler.lag.ms','payment.pg.active')}
        assert all(v is not None for v in probe.values()),probe
        (out/'metric-availability.json').write_text(json.dumps(probe))
        http(PG_PORT,'/__control',{'lose':True})
        status,result=http(APP_PORT,'/api/user/payments/confirm',{'orderId':'probe-order-1','paymentKey':'probe-key-1','amount':10000},{'X-Member-Id':'1'})
        assert status==409,(status,result)
        assert sql('SELECT status FROM payment WHERE id=1')=='TIMEOUT'
        watched={'method':'GET','path':'/v1/payments/probe-key-1'}
        http(PG_PORT,'/__control',{'lose':False,'delay_ms':pg_delay_ms,'watch_request':watched})
        assert http(APP_PORT,'/api/user/webhooks/payments',{'data':{'orderId':'probe-order-1','status':'CANCELED'}})[0]==200
        until(lambda:sql('SELECT status FROM payment_reconcile_job WHERE payment_id=1')=='PROCESSING',5)
        # A claimed DB job is not evidence of HTTP entry. Pin one actual server request
        # before inserting expiry fixtures, and prove it has not started a response at
        # the end of expiry/history/thread observation. A replacement GET cannot pass.
        entered=until(lambda:http(PG_PORT,'/__state')[1].get('watched_request'),5)
        overlap={'request_id':entered['request_id'],'overlap_verified':False,'events':[]}
        def record_overlap(phase,**details):
            overlap['events'].append({'phase':phase,'runner_ns':time.monotonic_ns(),**details})
            (out/'scheduler-overlap.json').write_text(json.dumps(overlap,indent=2))
        def assert_same_pending(phase):
            state=http(PG_PORT,'/__state')[1]; request=state.get('watched_request')
            record_overlap(phase,request=request,active_total=state['active'])
            assert state['active']>0,state
            assert request is not None and request['request_id']==overlap['request_id'],state
            assert {k:request[k] for k in ('method','path')}==watched,request
            assert request['entered_ns']==entered['entered_ns'],request
            assert request['active'] and request['response_started_ns'] is None and request['finished_ns'] is None,request
        assert_same_pending('pg-entered-before-fixture')
        record_overlap('fixture-insert-start')
        sql("""INSERT INTO reservation(id,member_id,store_id,table_id,reservation_start_at,reservation_end_at,party_size,status,requested_at,hold_expires_at)
            OVERRIDING SYSTEM VALUE VALUES (101,1,1,1,'2030-01-20 20:00+09','2030-01-20 21:00+09',2,'PENDING_PAYMENT','2030-01-20 11:00+09','2030-01-20 11:05+09');
            UPDATE waiting SET status='CALLED',called_at='2030-01-20 11:00+09',call_expires_at='2030-01-20 11:05+09',call_count=1 WHERE id=1;""")
        record_overlap('fixture-inserted')
        until(lambda:sql("SELECT status FROM reservation WHERE id=101")=='EXPIRED',1.5)
        until(lambda:sql("SELECT status FROM waiting WHERE id=1")=='NO_SHOW',1.5)
        assert_same_pending('expiry-observed')
        locks=sql('BEGIN; SELECT id FROM reservation WHERE id=1 FOR UPDATE NOWAIT; SELECT id FROM payment WHERE id=1 FOR UPDATE NOWAIT; SELECT payment_id FROM payment_reconcile_job WHERE payment_id=1 FOR UPDATE NOWAIT; ROLLBACK;')
        observed=observer(app,out) if observer else None
        assert_same_pending('observer-complete-before-kill')
        assert [e['phase'] for e in overlap['events']]==['pg-entered-before-fixture','fixture-insert-start',
            'fixture-inserted','expiry-observed','observer-complete-before-kill']
        assert all(a['runner_ns']<b['runner_ns'] for a,b in zip(overlap['events'],overlap['events'][1:]))
        overlap['overlap_verified']=True
        record_overlap('overlap-verified',observer_completed=observer is not None)
        pid=app.pid; stop(app,True); app=None
        record_overlap('jvm-killed',pid=pid)
        assert sql('SELECT status FROM payment_reconcile_job WHERE payment_id=1')=='PROCESSING'
        # Boundary-time lease expiry is separately tested with Clock in JUnit; accelerate only this process smoke.
        sql("UPDATE payment_reconcile_job SET lease_until='2029-01-01' WHERE payment_id=1")
        until(lambda:http(PG_PORT,'/__state')[1]['active']==0,max(5,pg_delay_ms/1000+1))
        http(PG_PORT,'/__control',{'delay_ms':0})
        app=start(source,out,'app-after-crash',True,app_args)
        approval_restart_pid=app.pid
        until(lambda:sql('SELECT status FROM payment WHERE id=1')=='DONE',10)
        assert sql('SELECT status FROM reservation WHERE id=1')=='CONFIRMED'
        assert http(PG_PORT,'/__state')[1]['rows']['probe-key-1']['approvals']==1
        # Actual refund HTTP path, effect persisted at fake PG before response loss, then process restart.
        http(PG_PORT,'/__control',{'lose':True})
        status,result=http(APP_PORT,'/api/user/payments/1/cancel',{'cancelReason':'고객 요청','cancelAmount':3000},
                           {'X-Member-Id':'1','Idempotency-Key':'probe-refund-original'})
        assert status==409,(status,result)
        assert sql('SELECT status FROM payment_refund WHERE payment_id=1')=='UNKNOWN'
        stop(app,True); app=None
        http(PG_PORT,'/__control',{'lose':False})
        app=start(source,out,'app-refund-restart',True,app_args)
        assert http(APP_PORT,'/api/user/webhooks/payments',{'orderId':'probe-order-1'})[0]==200
        until(lambda:sql('SELECT status FROM payment_refund WHERE payment_id=1')=='COMPLETED',10)
        until(lambda:sql('SELECT status FROM payment_reconcile_job WHERE payment_id=1')=='DONE',10)
        state=http(PG_PORT,'/__state')[1]
        assert state['rows']['probe-key-1']['cancels']==1
        assert sql('SELECT status FROM reservation_refund_request WHERE payment_id=1')=='COMPLETED'
        assert sql('SELECT status FROM payment WHERE id=1')=='PARTIAL_REFUNDED'
        assert state['rows']['probe-key-1']['balanceAmount']==7000
        report={'outcome':'passed','crashed_pid':pid,'approval_restart_pid':approval_restart_pid,'restart_pid':app.pid,'observation':observed,'scheduler_overlap':overlap,'lease_expiry':'forced SQL in process smoke; exact 119/120 sec boundaries in JUnit',
                'payment':sql('SELECT status FROM payment WHERE id=1'),'reservation':sql('SELECT status FROM reservation WHERE id=1'),
                'hold_expiry':'EXPIRED while PG active','waiting_expiry':'NO_SHOW while PG active','separate_connection_locks':locks,'pg':state}
        (out/'smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps({k:v for k,v in report.items() if k!='pg'},ensure_ascii=False))
    finally:stop(app)

def metric(name):
    try:
        code,body=http(APP_PORT,'/actuator/metrics/'+name,timeout=2)
        return body.get('measurements') if code==200 else None
    except Exception as error:
        return {'sampling_error':type(error).__name__}

def measure(out,delay_ms,requests,rate,warmup):
    http(PG_PORT,'/__control',{'delay_ms':delay_ms,'reset_metrics':True})
    routes=[('store','/api/user/stores?size=10',None),('reservation','/api/user/reservations/1',None),
            ('waiting','/api/user/waitings/1',None),('webhook','/api/user/webhooks/payments',{})]
    def one(i,scheduled):
        name,path,body=routes[i%len(routes)]
        if body is not None: body={'data':{'orderId':f'probe-order-{2+i%99}'}}
        start=time.monotonic()
        try:status,_=http(APP_PORT,path,body,{'X-Member-Id':'1'})
        except Exception:status=0
        return {'api':name,'status':status,'elapsed_ms':(time.monotonic()-start)*1000,
                'client_start_lag_ms':(start-scheduled)*1000,'index':i}
    # Identical warmup count and arrival rate for each source/PG condition.
    def phase(count):
        with concurrent.futures.ThreadPoolExecutor(max_workers=256) as pool:
            futures=[]; origin=time.monotonic()
            for i in range(count):
                scheduled=origin+i/rate; time.sleep(max(0,scheduled-time.monotonic()))
                futures.append(pool.submit(one,i,scheduled))
            return [f.result() for f in futures]
    phase(warmup)
    samples=[]; halted=threading.Event()
    names=['tomcat.threads.busy','tomcat.threads.current','hikaricp.connections.active','hikaricp.connections.pending',
           'payment.pg.active','payment.pg.admitted','payment.pg.rejected','probe.scheduler.lag.ms','jvm.threads.live']
    def sample():
        while not halted.is_set():
            row={'at':time.time(),'metrics':{n:metric(n) for n in names},'fake_pg':http(PG_PORT,'/__state')[1]}
            row['db']=sql("SELECT json_build_object('active',count(*) FILTER(WHERE state='active'),'idle_in_tx',count(*) FILTER(WHERE state='idle in transaction'),'lock_waiters',count(*) FILTER(WHERE wait_event_type='Lock')) FROM pg_stat_activity WHERE datname=current_database()")
            samples.append(row); halted.wait(.25)
    sampler=threading.Thread(target=sample); sampler.start()
    try:rows=phase(requests)
    finally:halted.set();sampler.join()
    summary={}
    for name,_,_ in routes:
        selected=[r for r in rows if r['api']==name]; latencies=sorted(r['elapsed_ms'] for r in selected)
        summary[name]={'n':len(selected),'p50':latencies[math.ceil(.5*len(latencies))-1],
                       'p95':latencies[math.ceil(.95*len(latencies))-1],'p99':latencies[math.ceil(.99*len(latencies))-1],
                       'error_rate':sum(not 200<=r['status']<300 for r in selected)/len(selected)}
    (out/'requests.json').write_text(json.dumps(rows));(out/'resources.json').write_text(json.dumps(samples))
    (out/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))

def baseline(out):
    source=out/'baseline';source.mkdir()
    archive=subprocess.Popen(['git','archive',BASE],cwd=ROOT,stdout=subprocess.PIPE)
    subprocess.run(['tar','-x','-C',str(source)],stdin=archive.stdout,check=True);archive.stdout.close()
    assert archive.wait()==0
    shutil.copytree(HERE,source/'scripts/payment-isolation',ignore=shutil.ignore_patterns('__pycache__'))
    build(source,out)
    return source

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--mode',choices=['smoke','prepare-baseline','validate-baseline','compare'],required=True)
    p.add_argument('--output',required=True);p.add_argument('--requests',type=int,default=4000);p.add_argument('--rate',type=float,default=100)
    p.add_argument('--warmup',type=int,default=400);p.add_argument('--repeats',type=int,default=3);args=p.parse_args()
    out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=False)
    (out/'environment.json').write_text(json.dumps({'baseline':BASE,'mode':args.mode,'source':str(ROOT),'argv':sys.argv,
        'clock':'2030-01-20T12:00+09 plus elapsed per JVM','db':f'{CONTAINER}/{DB_NAME}/{SCHEMA}','db_port':DB_PORT,'app_port':APP_PORT,'pg_port':PG_PORT,'java':str(JAVA)}))
    if args.mode=='prepare-baseline':baseline(out);sys.exit(0)
    check_ports();fake_log=(out/'fake.log').open('w')
    fake=subprocess.Popen([sys.executable,str(HERE/'fake_pg.py'),'--port',str(PG_PORT),'--ledger',str(out/'pg-ledger.json')],stdout=fake_log,stderr=subprocess.STDOUT)
    try:
        until(lambda:http(PG_PORT,'/__state',timeout=1)[0]==200,5)
        build(ROOT,out)
        if args.mode=='smoke':smoke(ROOT,out)
        elif args.mode=='validate-baseline':
            before=baseline(out);fixture(before,out);app=start(before,out,'baseline-fixture-app',True)
            try:
                paths=['/api/user/stores?size=10','/api/user/reservations/1','/api/user/waitings/1']
                checks={path:http(APP_PORT,path,headers={'X-Member-Id':'1'})[0] for path in paths}
                checks['webhook']=http(APP_PORT,'/api/user/webhooks/payments',{'orderId':'probe-order-2'})[0]
                assert set(checks.values())=={200},checks
                assert metric('probe.scheduler.lag.ms') is not None
                (out/'baseline-fixture.json').write_text(json.dumps(checks,indent=2))
                print(json.dumps(checks))
            finally:stop(app)
        else:
            before=baseline(out)
            for repeat in range(args.repeats):
                for label,source in [('before',before),('after',ROOT)]:
                    for condition,delay in [('normal',0),('pg-delay',2000)]:
                        dest=out/f'{repeat}-{label}-{condition}';dest.mkdir();fixture(source,dest);app=start(source,dest,'app')
                        try:measure(dest,delay,args.requests,args.rate,args.warmup)
                        finally:stop(app)
    finally:stop(fake);fake_log.close()
