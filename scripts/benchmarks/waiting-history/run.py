#!/usr/bin/env python3
"""Run only a validated historical C snapshot sequentially, offline."""
import fcntl
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
sys.dont_write_bytecode = True
from local_runtime import runtime, OFFLINE_FLAGS
from archive_logs import archive
from historical_input import validate_snapshot

ROOT = Path(__file__).resolve().parents[3]
if os.environ.get("RUN_WAITING_HISTORY_BENCHMARK") != "true":
    raise SystemExit("Stage 2 only: coordinator scheduling required; set RUN_WAITING_HISTORY_BENCHMARK=true then run")
snapshot = Path(sys.argv[1]).resolve()
if ROOT / "build/waiting-event-history/benchmarks" not in snapshot.parents:
    raise SystemExit("Use a snapshot inside this worktree's benchmark artifact directory")
try:
    manifest = json.loads((snapshot / "manifest.json").read_text())
    manifest, _ = validate_snapshot(snapshot, manifest["variant"])
except (OSError, ValueError, KeyError, TypeError) as error:
    raise SystemExit(f"Historical C preflight FAILED: {error}")
java_home, gradle = runtime()
lock = (ROOT / "build/waiting-event-history/benchmark-run.lock").open("w")
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit("Another C benchmark is running; runs must be sequential")
with socket.socket() as check:
    # A completed preceding HTTP run can leave TIME_WAIT sockets; this still rejects a live listener.
    check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    check.bind(("127.0.0.1", 18083))
container = "ct-improve-c-20261111"
info = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
if info["NetworkSettings"]["Ports"]["5432/tcp"] != [{"HostIp": "127.0.0.1", "HostPort": "55433"}]:
    raise SystemExit("Assigned database container/port mismatch")
psql = ["docker", "exec", container, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "ct_improve_c", "-d", "ct_improve_c"]
label = os.environ.get("BENCH_RUN_LABEL", "run")
if not re.fullmatch(r"[a-zA-Z0-9_-]+", label):
    raise SystemExit("BENCH_RUN_LABEL must use letters, digits, underscores or hyphens")
output = snapshot / "results" / f"{label}-{time.time_ns()}"
output.mkdir(parents=True, exist_ok=False)
print(output, flush=True)
subprocess.run([*psql, "-c", "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"], check=True)
subprocess.run([*psql, "-c", "SELECT count(*) FROM pg_stat_statements"], check=True)
# Never touch public/baseline_c data. Only the disposable schema is reset.
subprocess.run([*psql, "-c", "DROP SCHEMA IF EXISTS bench_c CASCADE; CREATE SCHEMA bench_c;"], check=True)
parameters = {key: int(os.environ.get(key, default)) for key, default in {
    "BENCH_WORKERS": "4", "BENCH_WAITINGS_PER_STORE": "10", "BENCH_WARMUP_PER_WORKER": "20",
    "BENCH_SAMPLES_PER_WORKER": "200", "BENCH_REPEATS": "3"}.items()}
env = dict(os.environ, JAVA_HOME=java_home, DB_HOST="127.0.0.1", DB_PORT="55433", DB_NAME="ct_improve_c",
           DB_USERNAME="ct_improve_c", DB_PASSWORD="ct_improve_c_local", SERVER_PORT="18083",
           SPRING_FLYWAY_SCHEMAS="bench_c",
           SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:55433/ct_improve_c?currentSchema=bench_c,public",
           TOSS_SECRET_KEY="", TOSS_BASE_URL="http://127.0.0.1:19083",
           SOLAPI_BASE_URL="http://127.0.0.1:19083", SOLAPI_API_KEY="", SOLAPI_API_SECRET="",
           ALIMTALK_ENABLED="true", ALIMTALK_MODE="simulation", BENCH_OUTPUT=str(output))
env.update({k: str(v) for k, v in parameters.items()})
command = [gradle, "test", "--tests", "com.catchtable.waiting.benchmark.WaitingHistoryBenchmark",
           "--rerun-tasks", *OFFLINE_FLAGS]
database_settings = subprocess.check_output([*psql, "-A", "-t", "-c", """
    SELECT json_object_agg(name,setting) FROM pg_settings WHERE name IN
    ('server_version','shared_preload_libraries','pg_stat_statements.track','max_connections',
     'shared_buffers','work_mem','effective_cache_size','jit','track_io_timing','fsync','synchronous_commit')
    """], text=True).strip()
environment = {"manifest": manifest, "java": subprocess.check_output([java_home + "/bin/java", "-version"], stderr=subprocess.STDOUT, text=True),
    "parameters": parameters, "container_image": info["Image"], "database_settings": json.loads(database_settings),
    "command": command, "host_loadavg_before": os.getloadavg(), "logical_cpus": os.cpu_count(),
    "started_at_epoch": time.time(), "state": "RUNNING", "local_only": True,
    "note": "Closed-loop workload, shared host; no real provider calls"}
(output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
with (output / "run.log").open("w") as log:
    result = subprocess.run(command, cwd=snapshot, env=env, stdout=log, stderr=subprocess.STDOUT)
archive(snapshot, output, not_before=environment["started_at_epoch"])
environment.update({"finished_at_epoch": time.time(), "host_loadavg_after": os.getloadavg(),
                    "exit_code": result.returncode, "state": "COMPLETED" if result.returncode == 0 else "FAILED",
                    "summary_exists": (output / "summary.json").is_file()})
(output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
if result.returncode == 0 and not environment["summary_exists"]:
    raise SystemExit("Gradle returned zero without benchmark output; execution is unproven")
print(output, flush=True)
raise SystemExit(result.returncode)
