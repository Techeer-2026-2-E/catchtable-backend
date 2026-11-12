#!/usr/bin/env python3
"""Run isolated source snapshots; --smoke is fixture/tool validation, --measure is phase 2."""
import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time

ROOT = Path(__file__).resolve().parents[2]
TOOL = Path(__file__).resolve().parent
BASELINE = 'a44952e37993e5003c860714f126057cd311dba5'
CONTAINER = 'ct-improve-a-20261111'
DB = 'ct_improve_a'
SCHEMA = 'availability_benchmark'


def command(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def snapshot(path, ref, working=False):
    path.mkdir()
    archive = subprocess.check_output(['git', 'archive', ref], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        # Archive is produced from the explicitly named local Git commit above.
        tar.extractall(path)
    if working:
        # Only the build inputs are overlaid; no local env files or credentials are copied.
        shutil.rmtree(path / 'src')
        shutil.copytree(ROOT / 'src', path / 'src')
        for filename in ['build.gradle', 'settings.gradle', 'gradlew', 'gradlew.bat']:
            shutil.copy2(ROOT / filename, path / filename)
    hashes = {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted((path / 'src').rglob('*')) if p.is_file()}
    (path / 'source-hashes.json').write_text(json.dumps(hashes, indent=2))


def resources():
    result = {'loadAverage': os.getloadavg(), 'timestamp': time.time()}
    result['dockerStats'] = subprocess.check_output(
        ['docker', 'stats', '--no-stream', '--format', '{{json .}}', CONTAINER], text=True).strip()
    return result


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true')
    mode.add_argument('--measure', action='store_true')
    parser.add_argument('--repetitions', type=int,
                        help='paired before/after repetitions (default: smoke 1, measure 3)')
    parser.add_argument('--warmup', type=int, default=50)
    parser.add_argument('--samples', type=int, default=200)
    args = parser.parse_args()
    repeats = args.repetitions if args.repetitions is not None else (1 if args.smoke else 3)
    if min(repeats, args.samples) < 1 or args.warmup < 0:
        parser.error('repetitions/samples must be positive, warmup nonnegative')
    info = json.loads(subprocess.check_output(['docker', 'inspect', CONTAINER], text=True))[0]
    ports = info['NetworkSettings']['Ports']['5432/tcp']
    if ports != [{'HostIp': '127.0.0.1', 'HostPort': '55431'}]:
        raise SystemExit('Refusing unexpected DB binding: ' + repr(ports))
    password = os.environ.get('AVAILABILITY_DB_PASSWORD')
    if not password:
        raise SystemExit('Set AVAILABILITY_DB_PASSWORD to this disposable container password')
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = ROOT / 'build' / 'availability-benchmark' / stamp
    out.mkdir(parents=True)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    snapshot(out / 'before', BASELINE)
    snapshot(out / 'after', head, working=True)
    (out / 'input.patch').write_bytes(subprocess.check_output(['git', 'diff', '--binary', 'HEAD'], cwd=ROOT))
    warmup, samples = (0, 1) if args.smoke else (args.warmup, args.samples)
    # Inherited SPRING_* overrides would outrank the dedicated DB_* target below.
    env = {k: v for k, v in os.environ.items() if not k.startswith('SPRING_')}
    env.update(DB_HOST='127.0.0.1', DB_PORT='55431', DB_NAME=DB,
               DB_USERNAME='catchtable', DB_PASSWORD=password,
               TOSS_SECRET_KEY='', TOSS_BASE_URL='http://127.0.0.1:19081',
               ALIMTALK_ENABLED='false', SOLAPI_API_KEY='', SOLAPI_API_SECRET='',
               SOLAPI_BASE_URL='http://127.0.0.1:19081',
               AVAILABILITY_BENCH_TOOL=str(TOOL),
               AVAILABILITY_BENCH_WARMUP=str(warmup), AVAILABILITY_BENCH_SAMPLES=str(samples),
               AVAILABILITY_BENCH_EXPLAIN=str(not args.smoke).lower())
    if args.smoke:
        env.update(AVAILABILITY_BENCH_STORES='1', AVAILABILITY_BENCH_TABLES='4', AVAILABILITY_BENCH_DAYS='2')
    else:
        env.update(AVAILABILITY_BENCH_STORES='3', AVAILABILITY_BENCH_TABLES='40', AVAILABILITY_BENCH_DAYS='14')
    (out / 'run.json').write_text(json.dumps({'baseline': BASELINE, 'afterBase': head,
        'mode': 'smoke' if args.smoke else 'measure', 'warmup': warmup, 'samples': samples,
        'repetitions': repeats, 'scope': 'sequential in-process full Spring MVC requests; no network',
        'schema': SCHEMA, 'dbPort': 55431}, indent=2))
    summary = []
    for repetition in range(repeats):
        responses = {}
        for variant in (['before', 'after'] if repetition % 2 == 0 else ['after', 'before']):
            result = out / f'{repetition + 1}-{variant}'
            result.mkdir()
            # This fixed schema belongs to this tool, in the assigned disposable DB only.
            command(['docker', 'exec', CONTAINER, 'psql', '-U', 'catchtable', '-d', DB,
                     '-v', 'ON_ERROR_STOP=1', '-c', f'DROP SCHEMA IF EXISTS {SCHEMA} CASCADE; CREATE SCHEMA {SCHEMA};'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            (result / 'resources-before.json').write_text(json.dumps(resources(), indent=2))
            env['AVAILABILITY_BENCH_OUT'] = str(result)
            cmd = ['./gradlew', 'test', '--tests', 'com.catchtable.reservation.benchmark.AvailabilityBenchmarkTest',
                   '--init-script', str(TOOL / 'benchmark.init.gradle'), '--no-daemon', '--max-workers=2', '--console=plain']
            (result / 'command.json').write_text(json.dumps(cmd, indent=2))
            print(f'{variant} repetition {repetition + 1}: {result}', flush=True)
            with (result / 'gradle.log').open('w') as log:
                command(cmd, cwd=out / variant, env=env, stdout=log, stderr=subprocess.STDOUT)
            shutil.copytree(out / variant / 'build/test-results/test', result / 'test-results')
            (result / 'resources-after.json').write_text(json.dumps(resources(), indent=2))
            raw = json.loads((result / 'samples.json').read_text())
            good = [r for r in raw if r['status'] == 200]
            row = {'variant': variant, 'repetition': repetition + 1, 'samples': len(raw),
                   'errorRate': 1 - len(good) / len(raw),
                   'totalSqlPerRequest': sorted(set(r['totalSql'] for r in good)),
                   'countSqlPerRequest': sorted(set(r['countSql'] for r in good))}
            if not args.smoke:
                ms = sorted(r['elapsedMs'] for r in good)
                row.update({f'p{p}Ms': ms[max(0, math.ceil(len(ms) * p / 100) - 1)] for p in (50, 95, 99)})
            summary.append(row)
            responses[variant] = json.loads((result / 'response.json').read_text())
        if responses['before'] != responses['after']:
            raise AssertionError('Baseline and modified responses differ')
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    print('Artifacts:', out)


if __name__ == '__main__':
    main()
