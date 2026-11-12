#!/usr/bin/env python3
"""Stage 1: prepare/compile; stage 2 (coordinator slot): run before, run after, summarize."""
import argparse
import csv
import hashlib
import io
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'build/business-hour-benchmark'
BASE = '4ac98d2034dfe17d80836667480c7d38459479ab'
FIXTURE = Path('src/test/java/com/catchtable/store/service/BusinessHourValidationFixture.java')
DB_ENV = dict(DB_HOST='localhost', DB_PORT='55442', DB_NAME='ct_pr_b',
              DB_USERNAME='ct_pr_b', DB_PASSWORD='ct_pr_b_local_only',
              SERVER_PORT='18092', TOSS_SECRET_KEY='benchmark-fake-key',
              TOSS_BASE_URL='http://localhost:19092', ALIMTALK_ENABLED='false',
              ALIMTALK_MODE='simulation')


def prepare():
    target = OUT / 'baseline'
    if not (target / '.benchmark-base').exists():
        if target.exists():
            raise SystemExit('Unmarked baseline directory exists; inspect it before selecting a fresh output directory.')
        target.mkdir(parents=True)
        archive = subprocess.check_output(['git', 'archive', BASE], cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            # Trusted fixed git archive, never user-provided archive bytes.
            source.extractall(target)
        (target / '.benchmark-base').write_text(BASE + '\n')
    if (target / '.benchmark-base').read_text().strip() != BASE:
        raise SystemExit('Baseline marker mismatch')
    shutil.copy2(ROOT / FIXTURE, target / FIXTURE)
    return target


def environment():
    # Inherited SPRING_* overrides would outrank the dedicated DB_* target below.
    env = {k: v for k, v in os.environ.items() if not k.startswith('SPRING_')}
    env.update(DB_ENV)
    env['B_BENCH_SOURCE_DIR'] = str(HERE)
    if 'JAVA_HOME' not in env:
        env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
    return env


def gradle(target, task, variant, env):
    OUT.mkdir(parents=True, exist_ok=True)
    command = [str(target / 'gradlew'), '-p', str(target), '--offline', '--no-daemon', '--max-workers=2',
               '-I', str(HERE / 'benchmark.init.gradle'), task]
    logfile = OUT / (variant + '-' + task + '.log')
    print(' '.join(command), flush=True)
    with logfile.open('w') as log:
        result = subprocess.run(command, cwd=target, env=env, stdout=log, stderr=subprocess.STDOUT)
    print('exit={}, log={}'.format(result.returncode, logfile), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


def provenance(target, variant, env):
    names = sorted((target / 'src/main').rglob('*')) + [target / FIXTURE, HERE / 'BusinessHourValidationBenchmark.java', HERE / 'benchmark.init.gradle']
    hashes = {str(p.relative_to(target)) if target in p.parents else str(p): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in names if p.is_file()}
    metadata = dict(variant=variant, baseline=BASE,
                    candidate_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    clock='2030-01-07T03:00:00Z', zone='Asia/Seoul',
                    fixture='7 weekly rows; 14 reservation dates; N confirmed reservations on N tables; one free competing table',
                    sizes=env.get('B_BENCH_SIZES', '0,10,1000'),
                    warmup=int(env.get('B_BENCH_WARMUP', '10')), samples=int(env.get('B_BENCH_SAMPLES', '100')),
                    repeats=int(env.get('B_BENCH_REPEATS', '3')), jvm='-Xms512m -Xmx512m -XX:ActiveProcessorCount=2',
                    java=subprocess.run([env['JAVA_HOME'] + '/bin/java', '-version'], capture_output=True, text=True).stderr,
                    postgres=subprocess.check_output(['docker', 'exec', 'ct-pr-b-20261113', 'psql', '-U',
                                                      'ct_pr_b', '-d', 'ct_pr_b', '-Atc', 'select version()'], text=True).strip(),
                    source_sha256=hashes)
    (OUT / (variant + '-environment.json')).write_text(json.dumps(metadata, indent=2) + '\n')


def summarize():
    settings = [{k: json.loads((OUT / (v + '-environment.json')).read_text())[k]
                 for k in ('sizes', 'warmup', 'samples', 'repeats')} for v in ('before', 'after')]
    if settings[0] != settings[1]:
        raise SystemExit('before/after measurement settings differ: {}'.format(settings))
    groups = {}
    for variant in ['before', 'after']:
        with (OUT / (variant + '-raw.csv')).open() as handle:
            for row in csv.DictReader(handle):
                for repeat in ['all', row['repeat']]:
                    key = (variant, repeat, row['operation'], row['reservations'], row['mode'], row['actor'])
                    groups.setdefault(key, []).append(row)
    metrics = ['request_ms', 'sql_count', 'config_selects', 'lock_acquire_ms', 'lock_hold_ms', 'observed_lock_wait_ms']
    summary = []
    for key, rows in groups.items():
        item = dict(zip(['variant', 'repeat', 'operation', 'reservations', 'mode', 'actor'], key))
        item.update(samples=len(rows), errors=sum(bool(row['error']) for row in rows),
                    observed_wait_requests=sum(int(row['wait_samples']) > 0 for row in rows))
        item['error_rate'] = item['errors'] / len(rows)
        for metric in metrics:
            values = sorted(float(row[metric]) for row in rows)
            item[metric] = {label: values[max(0, math.ceil(len(values) * fraction) - 1)]
                            for label, fraction in [('p50', .50), ('p95', .95), ('p99', .99)]}
        summary.append(item)
    path = OUT / 'summary.json'
    path.write_text(json.dumps(summary, indent=2) + '\n')
    print(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'compile', 'run', 'summarize'])
    parser.add_argument('--variant', choices=['before', 'after'])
    args = parser.parse_args()
    if args.action == 'summarize':
        summarize()
        return
    baseline = prepare()
    if args.action == 'prepare':
        print(baseline)
        return
    env = environment()
    if args.action == 'compile':
        for variant, target in [('before', baseline), ('after', ROOT)]:
            gradle(target, 'compileTestJava', variant, env)
        return
    if not args.variant:
        parser.error('run requires --variant before|after')
    if env.get('B_PERF_SLOT_APPROVED') != '1':
        raise SystemExit('Stage 2 only: coordinator assigns a sequential performance slot; then set B_PERF_SLOT_APPROVED=1.')
    target = baseline if args.variant == 'before' else ROOT
    env['B_BENCH_VARIANT'] = args.variant
    env['B_BENCH_RAW'] = str(OUT / (args.variant + '-raw.csv'))
    if Path(env['B_BENCH_RAW']).exists():
        raise SystemExit('Raw results already exist; preserve/rename the earlier run before rerunning.')
    provenance(target, args.variant, env)
    gradle(target, 'businessHourBenchmark', args.variant, env)


if __name__ == '__main__':
    main()
