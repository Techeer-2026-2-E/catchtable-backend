"""Capture real HTTP, backend and database samples in the isolated product lab."""

import concurrent.futures
import contextlib
import json
import math
import queue
import threading
import uuid
import os
import subprocess
import time
from http import client

from . import lab as L


API = "/api/user/waitings"


def http(port, method, path, member, body=None, key=None):
    connection = client.HTTPConnection(L.HTTP_HOST, port, timeout=12)
    headers = {"X-Member-Id": str(member), "Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    try:
        connection.request(
            method, path, None if body is None else json.dumps(body), headers
        )
        response = connection.getresponse()
        data = response.read()
        return (
            response.status,
            json.loads(data) if data else None,
            response.getheader("Location"),
            response.getheader("Retry-After"),
        )
    finally:
        connection.close()


def stop_process(proc):
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=4)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=4)


@contextlib.contextmanager
def sql_session(lab, db, *, begin=False, timeout=15):
    L.inspect_db(lab)
    if not 0 < timeout <= 120:
        raise ValueError("Invalid SQL barrier deadline")
    marker = "ct_barrier_" + uuid.uuid4().hex
    args = [
        *L.DOCKER,
        "exec",
        "-i",
        "-e",
        "PGAPPNAME=" + marker,
        "-e",
        f"PGOPTIONS=-c statement_timeout={int(timeout * 1000)} -c idle_in_transaction_session_timeout=180000",
        lab["id"],
        "psql",
        "-XqAt",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        L.ROLE,
        "-d",
        db,
    ]
    with subprocess.Popen(
        args, text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE
    ) as p:
        received = queue.Queue()

        def read():
            try:
                for line in p.stdout:
                    received.put(line.rstrip())
            finally:
                received.put(None)

        reader = threading.Thread(target=read, daemon=True)
        reader.start()

        def step(statement):
            sql = statement.rstrip()
            if not sql.endswith(";"):
                sql += ";"
            p.stdin.write(sql + "\n\\echo " + marker + " :SQLSTATE\n")
            p.stdin.flush()
            rows = []
            deadline = time.monotonic() + timeout
            while True:
                try:
                    line = received.get(timeout=max(0, deadline - time.monotonic()))
                except queue.Empty as error:
                    raise TimeoutError("SQL barrier deadline exceeded") from error
                if line is None:
                    raise RuntimeError("SQL barrier session ended")
                if line.startswith(marker + " "):
                    state = line.split()[-1]
                    if state != "00000":
                        raise RuntimeError("SQL barrier failed with SQLSTATE " + state)
                    return rows
                rows.append(line)

        try:
            if begin:
                step("BEGIN;")
            yield step
        finally:
            if p.poll() is None:
                p.stdin.close()
            try:
                p.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    L.sql(
                        lab,
                        db,
                        f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{marker}' AND datname=current_database()",
                    )
                finally:
                    stop_process(p)
            reader.join(timeout=2)


def distribution(values):
    if not values:
        return {"n": 0}
    ordered = sorted(values)
    return {
        "n": len(ordered),
        **{
            f"p{p}": round(ordered[math.ceil(len(ordered) * p / 100) - 1], 3)
            for p in (50, 95, 99)
        },
        "max": round(ordered[-1], 3),
    }


def other_windows(samples, config):
    # A diagnostic candidate, not an approved recovery time.
    before = config["Before"] * 1_000_000
    end = before + config["Window"] * 1_000_000
    post = config["After"] * 1_000_000
    width = 4_000_000
    other = [s for s in samples if s["Cohort"] == "other"]

    def window(start, stop):
        entries = {}
        for op, expected in (("availability", 200), ("waiting", 201)):
            rows = [s for s in other if start <= s["StartUS"] < stop and s["Op"] == op]
            ok = [s for s in rows if s["Outcome"] == f"http_{expected}"]
            entries[op] = {
                "total": len(rows),
                "success_ms": distribution(
                    [(s["EndUS"] - s["StartUS"]) / 1000 for s in ok]
                ),
            }
        return entries

    base = window(0, before)
    bins = []
    streak = 0
    recovery = None
    for start in range(end, end + post - width + 1, width):
        data = window(start, start + width)
        good = True
        for op, pct in (("waiting", 0.25), ("availability", 0.75)):
            now = data[op]["success_ms"]
            old = base[op]["success_ms"]
            minimum = int(pct * config["Background"] * width / 1e6)
            good &= (
                now["n"] == minimum
                and data[op]["total"] == minimum
                and old["n"] == minimum
                and now.get("p95", math.inf) <= 2 * old.get("p95", 0)
            )
        streak = streak + 1 if good else 0
        if streak >= 2 and recovery is None:
            recovery = (start + width - end) / 1e6
        bins.append(
            {"from_s": (start - end) / 1e6, "metrics": data, "within_candidate": good}
        )
    return {"before": base, "after": bins, "two_windows_end_s": recovery}


METRICS = (
    "hikaricp.connections.active",
    "hikaricp.connections.pending",
    "hikaricp.connections.acquire",
    "hikaricp.connections.timeout",
    "hikaricp.connections.max",
    "process.cpu.usage",
    "jvm.memory.used",
)


STATEMENTS = """SELECT coalesce(json_agg(row_to_json(q)),'[]') FROM (
 SELECT queryid::text,query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read,wal_bytes
 FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
 ORDER BY total_exec_time DESC) q;"""


def pool(port):
    result = {}
    for name in METRICS:
        try:
            path = (
                "/actuator/metrics/"
                + name
                + ("?tag=area:heap" if name == "jvm.memory.used" else "")
            )
            status, data = L.api(port, path, timeout=0.6)
            result[name] = (
                {m["statistic"]: m["value"] for m in data["measurements"]}
                if status == 200
                else {"http": status}
            )
        except (OSError, ValueError, KeyError) as e:
            result[name] = {"error": type(e).__name__}
            break
    return result


def monitor(lab, db, ports, ids, folder, client, stop):
    sql_text = (L.HERE / "monitor.sql").read_text()
    with (
        (folder / "observations.jsonl").open("x") as f,
        concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool_exec,
    ):
        while not stop.is_set():
            start = time.time()
            row = {"start": start, "host_load": os.getloadavg()}
            db_future = pool_exec.submit(L.sql, lab, db, sql_text)
            pool_futures = [pool_exec.submit(pool, p) for p in ports]
            try:
                row["db"] = json.loads(db_future.result())
                row["apps"] = [j.result() for j in pool_futures]
                if client.poll() is None:
                    row["client_cpu_rss_kib"] = subprocess.run(
                        ["ps", "-o", "%cpu=,rss=", "-p", str(client.pid)],
                        capture_output=True,
                        text=True,
                        check=False,
                    ).stdout.strip()
            except (OSError, ValueError, subprocess.SubprocessError) as e:
                row["error"] = type(e).__name__
            row["end"] = time.time()
            f.write(json.dumps(row) + "\n")
            f.flush()
            stop.wait(max(0, 1 - (time.time() - start)))
