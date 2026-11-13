#!/usr/bin/env python3
"""Read preserved baseline/modified results, verify counts, and write a comparison (no DB/network)."""
import collections
import argparse
from datetime import datetime
import json
import math
from pathlib import Path
import statistics
import sys
import tempfile
import traceback


def percentile(values, proportion):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * proportion) - 1)]


def latencies(rows):
    values = [row['millis'] for row in rows]
    return {'samples': len(values), 'errors': sum(row['status'] != 200 for row in rows),
            'p50_ms': percentile(values, .50), 'p95_ms': percentile(values, .95), 'p99_ms': percentile(values, .99)}


def groups(state):
    return collections.Counter({row['event_type']: row['count'] for row in state['events']})


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def classify(row):
    query = ' '.join(row['query'].lower().split())
    if 'pg_stat_activity' in query or 'pg_stat_statements_reset' in query:
        return 'instrumentation'
    if query.startswith(('begin', 'commit', 'rollback', 'set ', 'show ')):
        return 'transaction_control'
    if row['toplevel']:
        return 'top_level_business'
    if query.startswith('insert into waiting_event'):
        return 'nested_event_trigger'
    if 'for key share' in query:
        return 'nested_fk_check'
    return 'nested_other'


def describe(path, variant):
    environment = json.loads((path / 'environment.json').read_text())
    assert environment['state'] == 'COMPLETED' and environment['exit_code'] == 0, f"Run is not completed successfully: state={environment['state']}, exit_code={environment['exit_code']}"
    assert '--offline' in environment['command'], 'Run command does not include --offline'
    params = environment['parameters']
    workers = params['BENCH_WORKERS']; warmup = params['BENCH_WARMUP_PER_WORKER']
    samples = params['BENCH_SAMPLES_PER_WORKER']; size = params['BENCH_WAITINGS_PER_STORE']
    summary = json.loads((path / 'summary.json').read_text())
    assert len(summary) == params['BENCH_REPEATS']
    all_rows = []; all_resources = []; results = []; sql_total = collections.Counter()
    expected_sse_per_cycle = 4 * size + 12
    for s in summary:
        i = s['repeat']
        rows = json.loads((path / f'requests-{i}.json').read_text())
        warm = json.loads((path / f'warmup-{i}.json').read_text())
        resources = json.loads((path / f'resources-{i}.json').read_text())
        sql = json.loads((path / f'sql-{i}.json').read_text())
        assert len(rows) == workers * samples and len(warm) == workers * warmup
        assert all(row['status'] == 200 for row in rows + warm)
        assert s['errors'] == s['monitorErrors'] == 0
        for worker in range(workers):
            requests = sorted([r for r in rows if r['worker'] == worker], key=lambda r: r['index'])
            calls = warmup // 2; recalls = warmup // 4
            for n, request in enumerate(requests):
                assert request['index'] == n and request['operation'] == ['call', 'accept', 'recall', 'call'][n % 4]
                body = json.loads(request['response'])
                assert body['status'] == 'CALLED'
                assert instant(body['expiresAt']) == instant('2031-01-20T03:10:00Z')
                if request['operation'] == 'call':
                    calls += 1
                    assert body['callCount'] == calls
                elif request['operation'] == 'recall':
                    recalls += 1
                    assert body['callCount'] == calls and body['recallCount'] == recalls
                else:
                    assert body['arrivalIntent'] == 'COMING'
        before, after = s['stateBefore'], s['stateAfter']
        total_cycles = workers * (warmup + samples) // 4
        measured_cycles = workers * samples // 4
        assert after['outboxRows'] == total_cycles * 3
        assert after['outboxRows'] - before['outboxRows'] == measured_cycles * 3
        assert after['outbox'] == [{'status': 'PENDING', 'attempt_count': 0, 'count': total_cycles * 3}]
        assert len(after['waiting']) == workers
        for row in after['waiting']:
            assert row['status'] == 'CALLED' and row['responded_at'] is None
            assert row['call_count'] == (warmup + samples) // 2 and row['recall_count'] == (warmup + samples) // 4
        delta = groups(after) - groups(before)
        event_delta = after['eventRows'] - before['eventRows']
        sse_written = delta['SSE_EMIT_STARTED'] + delta['SSE_EMIT_FINISHED']
        if variant == 'modified':
            assert sum(v for k, v in delta.items() if not k.startswith(('SSE_', 'ALIMTALK_'))) == measured_cycles * 4
            assert delta['ALIMTALK_PENDING'] == measured_cycles * 3
            event_sql_rows = sum(q['rows'] for q in sql if ' '.join(q['query'].lower().split()).startswith('insert into waiting_event'))
            assert event_delta == event_sql_rows
            assert 0 <= sse_written <= measured_cycles * expected_sse_per_cycle
        else:
            assert event_delta == 0
        counts = collections.Counter()
        times = collections.defaultdict(float)
        wal = collections.defaultdict(int)
        for q in sql:
            kind = classify(q); counts[kind] += q['calls']; times[kind] += q['total_exec_time']; wal[kind] += q['wal_bytes']
        sql_total.update(counts)
        results.append({
            'repeat': i, **latencies(rows), 'sql_calls': dict(counts), 'sql_exec_ms_by_level': dict(times),
            'sql_wal_bytes_by_level': dict(wal), 'request_phase_seconds': s['requestPhaseSeconds'],
            'drain_ms': s['observationDrainMillis'], 'cpu_ms': s['cpuMillis'],
            'spring_transactions': s.get('springTransactions'),
            'events_measured_written': event_delta, 'events_measured_expected': measured_cycles * (7 + expected_sse_per_cycle) if variant == 'modified' else 0,
            'sse_measured_written': sse_written, 'sse_measured_expected': measured_cycles * expected_sse_per_cycle if variant == 'modified' else 0,
            'event_counts_measured': dict(delta), 'event_rows_including_warmup': after['eventRows'],
            'event_counts_including_warmup': dict(groups(after)),
            'event_bytes': after['eventBytes'], 'event_byte_growth': after['eventBytes'] - before['eventBytes'],
            'outbox_rows_including_warmup': after['outboxRows'], 'resource_samples': len(resources),
            'queue_max': max(r['observationQueued'] for r in resources),
            'lock_waiters_max': max(r['lockWaiters'] for r in resources),
            'pool_waiters_max': max(r['connectionsWaiting'] for r in resources),
            'pool_active_max': max(r['connectionsActive'] for r in resources),
            'host_cpu_load_mean': statistics.mean(r['hostCpuLoad'] for r in resources if r['hostCpuLoad'] >= 0),
            'host_loadavg_min': min(r['hostLoadAverage'] for r in resources),
            'host_loadavg_max': max(r['hostLoadAverage'] for r in resources),
            'heap_max_bytes': max(r['heapBytes'] for r in resources)})
        all_rows.extend(rows); all_resources.extend(resources)
    aggregate = latencies(all_rows)
    aggregate['error_rate'] = aggregate['errors'] / aggregate['samples']
    aggregate['sql_calls'] = dict(sql_total)
    aggregate['sse_expected'] = sum(r['sse_measured_expected'] for r in results)
    aggregate['sse_written'] = sum(r['sse_measured_written'] for r in results)
    aggregate['sse_missing'] = aggregate['sse_expected'] - aggregate['sse_written']
    aggregate['sse_missing_rate'] = aggregate['sse_missing'] / aggregate['sse_expected'] if aggregate['sse_expected'] else None
    aggregate['events_written'] = sum(r['events_measured_written'] for r in results)
    aggregate['events_expected'] = sum(r['events_measured_expected'] for r in results)
    aggregate['by_operation'] = {op: latencies([r for r in all_rows if r['operation'] == op]) for op in ['call','accept','recall']}
    aggregate['request_phase_seconds'] = sum(r['request_phase_seconds'] for r in results)
    aggregate['throughput_rps_closed_loop'] = len(all_rows) / aggregate['request_phase_seconds']
    aggregate['cpu_ms'] = sum(r['cpu_ms'] for r in results)
    warnings = json.loads((path / 'warnings.json').read_text())
    assert warnings['junitTests'] == 1 and warnings['junitFailures'] == warnings.get('junitErrors', 0) == warnings.get('junitSkipped', 0) == 0
    if variant == 'modified':
        including_warmup_missing = sum(workers * (warmup + samples) // 4 * (7 + expected_sse_per_cycle) - r['event_rows_including_warmup'] for r in results)
        assert warnings['queueRejected'] == including_warmup_missing, f"queueRejected={warnings['queueRejected']} does not match missing rows including warmup={including_warmup_missing}"
        assert warnings['writeFailed'] == 0, f"writeFailed={warnings['writeFailed']}; expected zero observation write failures"
    return {'path': str(path), 'parameters': params, 'environment': environment, 'repeats': results,
            'overall': aggregate, 'warnings_including_warmup': warnings}


def compare(baseline, modified, context):
    result = context['partial_comparison']
    context.update(stage='describe.baseline', input=str(baseline))
    result['baseline'] = describe(baseline, 'baseline')
    context.update(stage='describe.modified', input=str(modified))
    result['modified'] = describe(modified, 'modified')
    context.update(stage='compare.conditions', input=[str(baseline), str(modified)])
    assert result['baseline']['parameters'] == result['modified']['parameters']
    before = result['baseline']['environment']; after = result['modified']['environment']
    for key in ['java', 'database_settings', 'container_image', 'logical_cpus']:
        assert before[key] == after[key], key
    harness = 'src/test/java/com/catchtable/waiting/benchmark/WaitingHistoryBenchmark.java'
    for key in [harness, 'build.gradle', 'settings.gradle', 'src/main/resources/application.yml']:
        assert before['manifest']['files_sha256'][key] == after['manifest']['files_sha256'][key], key
    context.update(stage='compare.jvm', input=[str(path / 'jvm.json') for path in [baseline, modified]])
    jvms = [json.loads((path / 'jvm.json').read_text()) for path in [baseline, modified]]
    for jvm in jvms:
        jvm['arguments'] = [a for a in jvm['arguments'] if not a.startswith('-Dorg.gradle.internal.worker.tmpdir=')]
    assert jvms[0] == jvms[1]
    context.update(stage='compare.sequence_and_statistics', input=[str(baseline), str(modified)])
    assert result['baseline']['environment']['finished_at_epoch'] <= result['modified']['environment']['started_at_epoch']
    result['delta'] = {key: {'absolute_ms': result['modified']['overall'][key] - result['baseline']['overall'][key],
                           'percent': (result['modified']['overall'][key] / result['baseline']['overall'][key] - 1) * 100}
                       for key in ['p50_ms','p95_ms','p99_ms']}
    observations_complete = (result['modified']['overall']['events_written'] == result['modified']['overall']['events_expected']
                             and result['modified']['warnings_including_warmup']['queueRejected'] == 0
                             and result['modified']['warnings_including_warmup']['writeFailed'] == 0
                             and result['modified']['warnings_including_warmup'].get('shutdownUnknown', 0) == 0)
    params = result['modified']['parameters']
    full_phase_expected = params['BENCH_WORKERS'] * (params['BENCH_WARMUP_PER_WORKER'] + params['BENCH_SAMPLES_PER_WORKER']) // 4 * (4 * params['BENCH_WAITINGS_PER_STORE'] + 12) // 2
    for repeat in result['modified']['repeats']:
        for phase in ['SSE_EMIT_STARTED', 'SSE_EMIT_FINISHED']:
            observations_complete &= repeat['event_counts_measured'].get(phase, 0) == repeat['sse_measured_expected'] // 2
            observations_complete &= repeat['event_counts_including_warmup'].get(phase, 0) == full_phase_expected
    result['observations_complete'] = observations_complete
    return result


def write_result(output, result):
    # Replace atomically so a validation failure cannot leave an old success or a truncated new JSON.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=output.parent, prefix=f'.{output.name}.',
                                         encoding='utf-8', delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(result, stream, indent=2)
            stream.write('\n')
        temporary.replace(output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('baseline', type=Path)
    parser.add_argument('modified', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--require-complete-observations', action='store_true',
                        help='Fail acceptance when any measured/warmup observation is missing')
    args = parser.parse_args()
    context = {'stage': 'input', 'input': None, 'partial_comparison': {}}
    exit_code = 0
    try:
        result = compare(args.baseline, args.modified, context)
        context.update(stage='compare.completeness', input=str(args.modified))
        if args.require_complete_observations and not result['observations_complete']:
            raise ValueError('Observation completeness FAILED (not loss-free cost)')
    except Exception as error:
        frame = traceback.extract_tb(error.__traceback__)[-1]
        reason = str(error) or f'Validation failed: {frame.line or frame.name}'
        result = {'status': 'FAILED',
                  'inputs': {'baseline': str(args.baseline), 'modified': str(args.modified)},
                  'error': {'type': type(error).__name__, 'reason': reason,
                            'stage': context['stage'], 'input': context['input'],
                            'location': {'file': Path(frame.filename).name, 'line': frame.lineno, 'function': frame.name}},
                  'partial_comparison': context['partial_comparison']}
        print(f"Analysis FAILED at {context['stage']} ({context['input']}): {reason}", file=sys.stderr)
        exit_code = 1
    try:
        write_result(args.output, result)
    except OSError as error:
        print(f'Cannot write result to {args.output}: {error}; any existing output is stale for this invocation', file=sys.stderr)
        return 2
    if exit_code:
        return exit_code
    for variant in ['baseline','modified']:
        print(variant, json.dumps(result[variant]['overall'], indent=2))
    print('delta', result['delta'])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
