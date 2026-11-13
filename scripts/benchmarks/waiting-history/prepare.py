#!/usr/bin/env python3
"""Prepare only the pinned historical C pair; --check-only is file-only."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
sys.dont_write_bytecode = True
from local_runtime import runtime, OFFLINE_FLAGS
from historical_input import BASE, validate_snapshot

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("variant", choices=["baseline", "modified"])
    parser.add_argument("--historical-snapshot", type=Path,
                        help="Explicit preserved snapshot directory containing manifest.json (required)")
    parser.add_argument("--compile", action="store_true")
    parser.add_argument("--check-only", action="store_true", help="Validate files only; no output/runtime/DB")
    args = parser.parse_args()
    if args.historical_snapshot is None:
        parser.error("Historical C input required: specify --historical-snapshot; current durable SSE is unsupported")
    if args.check_only and args.compile:
        parser.error("--check-only cannot be combined with --compile")
    try:
        original, payload = validate_snapshot(args.historical_snapshot, args.variant)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Historical C preflight FAILED: {error}\n")
    if args.check_only:
        print(json.dumps({"status": "VALID_HISTORICAL_INPUT", "variant": args.variant,
                          "files": len(payload), "runtime_or_measurement_executed": False}))
        return
    java_home, gradle = runtime() if args.compile else (None, None)
    destination = ROOT / "build/waiting-event-history/benchmarks" / f"{args.variant}-{time.time_ns()}"
    destination.mkdir(parents=True)
    for name, data in payload.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / "gradlew").chmod(0o755)
    (destination / "manifest.json").write_text(json.dumps({
        "variant": args.variant, "base": BASE,
        "head": original["head"], "historical_input": str(args.historical_snapshot.resolve()),
        "files_sha256": original["files_sha256"], "app_port": 18083, "db_port": 55433,
        "db_name": "ct_improve_c", "schema": "bench_c", "clock": "2031-01-20T03:00:00Z",
        "gradle": gradle, "offline": True,
    }, indent=2) + "\n")
    if args.compile:
        env = dict(os.environ)
        env["JAVA_HOME"] = java_home
        with (destination / "compile.log").open("w") as log:
            subprocess.run([gradle, "testClasses", *OFFLINE_FLAGS],
                           cwd=destination, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(destination)


if __name__ == "__main__":
    main()
