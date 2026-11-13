#!/usr/bin/env python3
"""File-only regression checks: copy smoke fixtures, inject analyzer input faults, preserve every result."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before-analyzer', type=Path, required=True)
    parser.add_argument('--smoke-runs', type=Path, required=True)
    parser.add_argument('--loss-runs', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = args.output_dir.resolve()
    root.mkdir(parents=True, exist_ok=False)
    analyzer = Path(__file__).with_name('analyze.py').resolve()
    smoke = json.loads(args.smoke_runs.read_text())
    records = []

    def fixture(name):
        folder = root / name
        folder.mkdir()
        paths = {}
        for variant in ['baseline', 'modified']:
            paths[variant] = folder / variant
            shutil.copytree(smoke[variant], paths[variant])
        return paths

    def mutate(path, key, value):
        content = json.loads(path.read_text())
        content[key] = value
        path.write_text(json.dumps(content, indent=2) + '\n')

    def run(name, script, paths, output):
        command = [sys.executable, str(script), str(paths['baseline']), str(paths['modified']),
                   str(output), '--require-complete-observations']
        result = subprocess.run(command, capture_output=True, text=True)
        (root / f'{name}.stdout.log').write_text(result.stdout)
        (root / f'{name}.stderr.log').write_text(result.stderr)
        records.append({'case': name, 'command': command, 'exit_code': result.returncode,
                        'output': str(output), 'output_is_file': output.is_file()})
        return result

    def failed(result, output, stage, reason):
        assert result.returncode == 1, result.stderr
        data = json.loads(output.read_text())
        assert data['status'] == 'FAILED'
        assert data['error']['stage'] == stage
        assert reason in data['error']['reason'], data['error']
        assert data['error']['input'] and data['error']['location']['line'] > 0
        assert 'partial_comparison' in data
        assert 'Analysis FAILED' in result.stderr
        assert 'observations_complete' not in data  # never present a partial result as the old success shape
        return data

    normal = fixture('normal-fixture')
    original_output = root / 'normal-before.json'
    success_output = root / 'normal-after.json'
    assert run('normal-before', args.before_analyzer, normal, original_output).returncode == 0
    assert run('normal-after', analyzer, normal, success_output).returncode == 0
    assert success_output.read_bytes() == original_output.read_bytes()  # existing success contract unchanged
    assert json.loads(success_output.read_text())['observations_complete'] is True

    for name, field in [('write-failed', 'writeFailed'), ('queue-mismatch', 'queueRejected')]:
        paths = fixture(name)
        mutate(paths['modified'] / 'warnings.json', field, 1)
        before_output = root / f'{name}-before.json'
        before_result = run(name + '-before', args.before_analyzer, paths, before_output)
        assert before_result.returncode != 0 and not before_output.exists()  # reproduce SPEC-1 in the old tool
        output = root / f'{name}-after.json'
        failed(run(name + '-after', analyzer, paths, output), output, 'describe.modified', field + '=1')

    early = fixture('early-input')
    mutate(early['baseline'] / 'environment.json', 'state', 'RUNNING')
    early_output = root / 'early-input.json'
    failed(run('early-input', analyzer, early, early_output), early_output, 'describe.baseline', 'state=RUNNING')

    malformed = fixture('malformed-json')
    (malformed['baseline'] / 'environment.json').write_text('{')
    output = root / 'malformed-json.json'
    data = failed(run('malformed-json', analyzer, malformed, output), output, 'describe.baseline', 'property name')
    assert data['error']['type'] == 'JSONDecodeError'

    missing = fixture('missing-input')
    (missing['baseline'] / 'environment.json').unlink()
    output = root / 'missing-input.json'
    failed(run('missing-input', analyzer, missing, output), output, 'describe.baseline', 'environment.json')

    # A prior valid output must not survive a new validation failure as an apparent success.
    reused = root / 'reused-output.json'
    shutil.copy2(success_output, reused)
    failed(run('replace-stale-success', analyzer, early, reused), reused, 'describe.baseline', 'state=RUNNING')
    assert reused.read_bytes() != success_output.read_bytes()

    loss = json.loads(args.loss_runs.read_text())
    before_loss = root / 'loss-before.json'
    assert run('original-loss-before', args.before_analyzer, loss, before_loss).returncode == 1
    after_loss = root / 'loss-after.json'
    data = failed(run('original-loss-after', analyzer, loss, after_loss), after_loss,
                  'compare.completeness', 'Observation completeness FAILED')
    assert data['partial_comparison'] == json.loads(before_loss.read_text())
    assert data['partial_comparison']['observations_complete'] is False

    unwritable = root / 'output-is-directory'
    unwritable.mkdir()
    result = run('unwritable-output', analyzer, normal, unwritable)
    assert result.returncode == 2 and unwritable.is_dir()
    assert 'Cannot write result' in result.stderr and 'stale' in result.stderr
    assert not list(root.glob('.output-is-directory.*'))  # no incomplete temporary file retained

    summary = {'status': 'PASSED', 'scope': 'file-only synthetic analyzer faults; no product/DB/load execution',
               'before_analyzer_sha256': hashlib.sha256(args.before_analyzer.read_bytes()).hexdigest(),
               'after_analyzer_sha256': hashlib.sha256(analyzer.read_bytes()).hexdigest(),
               'success_json_byte_identical': True, 'invocations': records}
    (root / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'status': 'PASSED', 'invocations': len(records), 'evidence': str(root)}, indent=2))


if __name__ == '__main__':
    main()
