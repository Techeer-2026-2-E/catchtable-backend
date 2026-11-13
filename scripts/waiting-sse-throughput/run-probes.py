#!/usr/bin/env python3
"""Sequential, local-only causal comparison against a preserved 559-file build copy."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--baseline', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--tag', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
baseline, output = Path(args.baseline).resolve(), Path(args.output).resolve()
if not str(baseline).startswith(str(root / 'build') + '/') or not str(output).startswith(str(root / 'build') + '/'):
    raise SystemExit('baseline and evidence must be under this worktree build/')
if not args.tag.isalnum():
    raise SystemExit('tag must be alphanumeric')
output.mkdir(parents=True, exist_ok=False)
env = os.environ.copy()
env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
env['WAITING_SSE_PROBE_SOURCE'] = str(Path(__file__).parent.resolve())
command = ['./gradlew', '--offline', '--no-daemon', '--max-workers=2',
           '-Dorg.gradle.java.installations.auto-download=false', '-I',
           str(Path(__file__).with_name('probe.init.gradle').resolve()), 'runWaitingSseThroughput']
results = []
for connections in [0, 1]:
    for label, cwd in [('before', baseline), ('after', root)]:
        name = '{}-c{}'.format(label, connections)
        result_path = output / (name + '.json')
        run_env = dict(env, WAITING_SSE_PROBE_SCHEMA='l_tput_{}_c{}_{}'.format(label, connections, args.tag),
                       WAITING_SSE_PROBE_OUTPUT=str(result_path), WAITING_SSE_PROBE_CONNECTIONS=str(connections))
        started = time.time()
        with (output / (name + '.log')).open('w') as log:
            process = subprocess.run(command, cwd=cwd, env=run_env, stdout=log, stderr=subprocess.STDOUT)
        receipt = {'name': name, 'cwd': str(cwd), 'command': command, 'exit_code': process.returncode,
                   'started_epoch': started, 'finished_epoch': time.time(), 'schema': run_env['WAITING_SSE_PROBE_SCHEMA'],
                   'worker_sha256': hashlib.sha256((cwd / 'src/main/java/com/catchtable/notification/durability/WaitingSseWorker.java').read_bytes()).hexdigest(),
                   'probe_sha256': hashlib.sha256(Path(__file__).with_name('WaitingSseThroughputProbe.java').read_bytes()).hexdigest()}
        (output / (name + '.command.json')).write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt), flush=True)
        if process.returncode:
            raise SystemExit(process.returncode)
        results.append({'name': name, 'result': json.loads(result_path.read_text())})
(output / 'comparison.json').write_text(json.dumps(results, indent=2) + '\n')
