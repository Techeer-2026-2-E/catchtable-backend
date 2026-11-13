#!/usr/bin/env python3
"""Loopback-only HTTP PG with a separate durable ledger; no external network."""
import argparse, json, threading, time
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

class Pg:
    def __init__(self, ledger):
        self.path=Path(ledger); self.lock=threading.RLock()
        self.rows=json.loads(self.path.read_text()) if self.path.exists() else {}
        self.delay_ms=0; self.error=0; self.lose=False
        self.active=0; self.peak=0; self.calls=0
        # Opt-in correctness observation only; comparison mode never enables it.
        self.watch_request=None; self.watched_request=None; self.request_sequence=0
    def save(self):
        temp=self.path.with_suffix('.tmp'); temp.write_text(json.dumps(self.rows)); temp.replace(self.path)

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def read_body(self):
        if self.headers.get('Transfer-Encoding','').lower()=='chunked':
            body=bytearray()
            while True:
                size=int(self.rfile.readline().split(b';')[0].strip(),16)
                if size==0:
                    self.rfile.readline(); break
                body.extend(self.rfile.read(size)); self.rfile.read(2)
                if len(body)>1024*1024: raise ValueError('body too large')
            return bytes(body)
        return self.rfile.read(int(self.headers.get('Content-Length','0')))
    def send(self,code,body):
        watched=getattr(self,'watched_request',None)
        if watched is not None:
            with self.server.pg.lock: watched['response_started_ns']=time.monotonic_ns()
        payload=json.dumps(body).encode(); self.send_response(code)
        self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(payload)))
        self.end_headers(); self.wfile.write(payload)
    def do_GET(self): self.handle_request()
    def do_POST(self): self.handle_request()
    def handle_request(self):
        pg=self.server.pg
        self.watched_request=None
        body=json.loads(self.read_body() or '{}') if self.command=='POST' else {}
        if self.path=='/__control':
            with pg.lock:
                for k in ('delay_ms','error','lose'):
                    if k in body: setattr(pg,k,body[k])
                if 'seed' in body: pg.rows=body['seed']; pg.save()
                if body.get('reset_metrics'): pg.peak=pg.active; pg.calls=0
                if 'watch_request' in body:
                    pg.watch_request=body['watch_request']; pg.watched_request=None
            return self.send(200,{'ok':True})
        if self.path=='/__state':
            with pg.lock:
                snapshot={'rows':pg.rows,'active':pg.active,'peak':pg.peak,'calls':pg.calls}
                if pg.watch_request is not None:
                    snapshot['watched_request']=dict(pg.watched_request) if pg.watched_request is not None else None
            return self.send(200,snapshot)
        with pg.lock:
            pg.active+=1; pg.peak=max(pg.active,pg.peak); pg.calls+=1
            delay,error,lose=pg.delay_ms,pg.error,pg.lose
            if pg.watch_request is not None and pg.watch_request=={'method':self.command,'path':self.path}:
                pg.request_sequence+=1
                self.watched_request={'request_id':pg.request_sequence,'method':self.command,'path':self.path,
                    'entered_ns':time.monotonic_ns(),'active':True,'response_started_ns':None,'finished_ns':None}
                pg.watched_request=self.watched_request
        try:
            time.sleep(delay/1000)
            if error: return self.send(error,{'code':'FAKE_ERROR','message':'local failure'})
            with pg.lock:
                if self.path.endswith('/confirm'): row=pg.rows.get(body.get('paymentKey'))
                elif '/orders/' in self.path:
                    row=next((r for r in pg.rows.values() if r['orderId']==self.path.rsplit('/',1)[-1]),None)
                else: row=pg.rows.get(self.path.split('/')[3])
                if row is None: return self.send(404,{'code':'NOT_FOUND','message':'synthetic order missing'})
                if self.command=='POST':
                    key=self.headers.get('Idempotency-Key')
                    if key not in row.setdefault('keys',[]):
                        row['keys'].append(key)
                        if self.path.endswith('/confirm'):
                            row['status']='DONE'; row['approvals']=row.get('approvals',0)+1
                        else:
                            row['balanceAmount']-=body['cancelAmount']; row['cancels']=row.get('cancels',0)+1
                            row['status']='CANCELED' if row['balanceAmount']==0 else 'PARTIAL_CANCELED'
                        pg.save()
                    if lose:
                        self.close_connection=True
                        return
                return self.send(200,row)
        except (BrokenPipeError,ConnectionResetError): pass
        finally:
            with pg.lock:
                pg.active-=1
                if self.watched_request is not None:
                    self.watched_request['active']=False
                    self.watched_request['finished_ns']=time.monotonic_ns()

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--ledger',required=True); parser.add_argument('--port',type=int,default=19084); args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler); server.pg=Pg(args.ledger); server.serve_forever()
