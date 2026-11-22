"""Real HTTP intake trials. Uses the isolated copy of the November OFF harness."""

import argparse
import concurrent.futures
import csv
import datetime as dt
import hashlib
import json
import subprocess
import sys
import threading
import time
from collections import Counter, defaultdict
from http import client, server
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "build/intake-lab/tools"))
import lab as L
from analyze import access_logs, classify, distribution, other_windows
from run import STATEMENTS, monitor, pool

API = "/api/user/waitings"
CASES = {"w1k": (1000, 10), "w5k": (5000, 10), "w10k": (10000, 10), "open": (10000, 1)}


def http(port, method, path, member, body=None, key=None):
    connection = client.HTTPConnection("127.0.0.1", port, timeout=12)
    headers = {"X-Member-Id": str(member), "Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    try:
        connection.request(method, path, None if body is None else json.dumps(body), headers)
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


def smoke(ports, folder):
    member, body, key = 500, {"storeId": 1, "partyCount": 2}, "lost-on-first-send"
    received = []

    class DropResponse(server.BaseHTTPRequestHandler):
        def do_POST(self):
            forwarded = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(http(ports[0], "POST", API, member, forwarded, self.headers["Idempotency-Key"]))
            self.close_connection = True

    with server.HTTPServer(("127.0.0.1", 0), DropResponse) as proxy:
        thread = threading.Thread(target=proxy.handle_request)
        thread.start()
        try:
            http(proxy.server_port, "POST", API, member, body, key)
        except client.RemoteDisconnected:
            lost = True
        else:
            lost = False
        thread.join(timeout=15)
    assert lost and len(received) == 1
    status, receipt, location, hint = received[0]
    assert (
        status == 202 and receipt["intakeSequence"] == 1 and location and hint and "waitNumber" not in receipt
    )
    replay = http(ports[1], "POST", API, member, body, key)
    alias = http(ports[2], "POST", API, member, body, "new-key")
    assert replay == alias == (200, receipt, location, hint)
    assert http(ports[2], "GET", location, member)[:2] == (200, receipt)
    assert http(ports[0], "GET", location, 501)[0] == 404
    assert http(ports[1], "POST", API, 501, body, "over-capacity")[0] == 429
    assert http(ports[1], "POST", API, member, dict(body, partyCount=4), key)[0] == 409
    b = dict(body, storeId=2)
    assert http(ports[1], "POST", API, member, b, key)[0] == 409
    assert http(ports[1], "POST", API, member, b)[0] == 201
    assert http(ports[0], "POST", API, member, b)[0] == 200
    L.save(
        folder / "lost-response.json",
        {
            "client": "RemoteDisconnected",
            "first": received[0],
            "retry_other_app": replay,
            "alias_third_app": alias,
        },
    )

    # Two different apps compete for the last slot at the auxiliary ledger store.
    barrier = threading.Barrier(2)

    def race(index):
        barrier.wait(timeout=5)
        return http(ports[index], "POST", API, 600 + index, {"storeId": 3, "partyCount": 2}, f"race-{index}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as threads:
        results = list(threads.map(race, (0, 1)))
    assert sorted(r[0] for r in results) == [202, 429]
    L.save(folder / "last-slot.json", results)


def warm(ports, date):
    for i in range(36):
        port = ports[i % len(ports)]
        assert (
            http(port, "GET", f"/api/user/stores/{1 + i % 2}/availability?date={date}&partySize=2", 4)[0]
            == 200
        )
        assert http(port, "POST", API, 100 + i, {"storeId": 3, "partyCount": 2}, f"warm-{i}")[0] == 202
    for i in range(6):
        status, response, *_ = http(
            ports[i % len(ports)],
            "POST",
            "/api/user/reservations/holds",
            200 + i,
            {"storeId": 3, "partySize": 2, "reservationStartAt": date + "T12:00:00+09:00"},
        )
        assert status == 201
        assert (
            http(
                ports[(i + 1) % len(ports)],
                "DELETE",
                "/api/user/reservations/holds/" + str(response["holdId"]),
                200 + i,
            )[0]
            == 204
        )


def run_client(lab, db, ports, ids, folder):
    L.save(folder / "pool-before.json", [pool(p) for p in ports])
    L.sql(lab, db, "SELECT pg_stat_statements_reset();")
    stop = threading.Event()
    with (folder / "containers.log").open("x") as stats, (folder / "client.log").open("x") as log:
        stat = subprocess.Popen(
            ["docker", "--context", "desktop-linux", "stats", "--format", "{{json .}}", lab["id"], *ids],
            stdout=stats,
            stderr=subprocess.STDOUT,
        )
        try:
            with subprocess.Popen(
                [L.OUT / "client", folder / "config.json", folder], stdout=log, stderr=subprocess.STDOUT
            ) as proc:
                sampler = threading.Thread(target=monitor, args=(lab, db, ports, ids, folder, proc, stop))
                sampler.start()
                try:
                    if proc.wait(timeout=150):
                        raise RuntimeError("Client did not complete")
                finally:
                    stop.set()
                    sampler.join()
        finally:
            stat.terminate()
            stat.wait(timeout=10)
    L.save(folder / "pool-after.json", [pool(p) for p in ports])
    (folder / "statements.json").write_text(L.sql(lab, db, STATEMENTS) + "\n")


def summarize(folder):
    cfg = json.loads((folder / "config.json").read_text())
    samples = [json.loads(line) for line in (folder / "client.jsonl").read_text().splitlines()]
    with (folder / "receipts.csv").open() as f:
        rows = list(csv.DictReader(f))
    with (folder / "keys.csv").open() as f:
        keys = list(csv.DictReader(f))
    with (folder / "waiting.csv").open() as f:
        waiting = list(csv.DictReader(f))
    by_member = {int(r["member_id"]): r for r in rows}
    aliases = {(int(r["member_id"]), r["request_key"]): r["receipt_id"] for r in keys}
    begin = cfg["Before"] * 1e6
    end = begin + cfg["Window"] * 1e6

    def phase(t):
        if t < begin:
            return "before"
        return "spike" if t < end else "after"

    hot = [x for x in samples if x["Cohort"] == "hot"]
    expected = set(range(1000, 1000 + cfg["Users"]))
    ack = [x for x in hot if x["Outcome"] == "http_202"]
    delays = distribution([(x["EndUS"] - x["StartUS"]) / 1000 for x in ack])
    bad = []
    for x in ack:
        m = x["Member"]
        r = by_member.get(m)
        rep = x["Reply"]
        if not r or not (
            r["receipt_id"] == rep.get("receiptId") == aliases.get((m, f"intake-{m}"))
            and int(r["intake_sequence"]) == rep.get("intakeSequence")
            and rep["storeId"] == 1
            and rep.get("partyCount") == 2
            and rep.get("status") == r["status"] == "PENDING"
            and rep.get("receivedDate") == r["received_date"] == cfg["day"]
            and x["Location"] == API + "/receipts/" + r["receipt_id"]
            and x["RetryAfter"] == "2"
        ):
            bad.append(m)
    bg = {int(r["member_id"]): r for r in waiting if r["store_id"] == "2"}
    bg_ack = [
        x for x in samples if x["Cohort"] == "other" and x["Op"] == "waiting" and x["Outcome"] == "http_201"
    ]
    other = [x for x in samples if x["Cohort"] == "other"]
    expected_other = cfg["Background"] * (cfg["Before"] + cfg["Window"] + cfg["After"])
    scheduled_other = len(other) == expected_other and len({x["Member"] for x in other}) == expected_other
    bad_reads = [
        x["Member"]
        for x in other
        if x["Op"] == "availability"
        and x["Status"] == 200
        and (x["Slots"] != 44 or x["Available"] != 12 or x["Reply"]["storeId"] != 2)
    ]
    bg_bad = [
        x["Member"]
        for x in bg_ack
        if x["Member"] not in bg
        or not (
            int(bg[x["Member"]]["id"]) == x["Reply"]["waitingId"]
            and int(bg[x["Member"]]["wait_number"]) == x["Reply"]["waitNumber"]
        )
    ]
    started = sum(begin <= x["StartUS"] < end for x in hot)
    wire = sum(begin <= x["WrittenUS"] < end for x in hot)
    origin = int((folder / "origin-us.txt").read_text())
    access = access_logs(folder)
    seen = [access[x["ID"]] for x in hot if x["ID"] in access]
    logged_missing = sum(x["Status"] > 0 and x["ID"] not in access for x in samples)
    second_delays = [x["EndUS"] - x["StartUS"] for x in ack]
    groups = defaultdict(list)
    completed = Counter()
    for x in samples:
        completed["/".join((x["Cohort"], x["Op"], classify(x), phase(x["EndUS"])))] += 1
        groups["/".join((x["Cohort"], x["Op"], classify(x), phase(x["StartUS"])))].append(
            (x["EndUS"] - x["StartUS"]) / 1000
        )
    sequence_gaps = sorted(int(r["intake_sequence"]) for r in rows) != list(range(1, len(rows) + 1))
    duplicate_members = len(rows) - len(by_member)
    duplicate_ids = len(rows) - len({r["receipt_id"] for r in rows})
    invalid_keys = sum(
        aliases.get((member, f"intake-{member}")) != r["receipt_id"] for member, r in by_member.items()
    )
    finished = iter(sorted(ack, key=lambda x: x["EndUS"]))
    predecessor = next(finished, None)
    last_confirmed = 0
    comparable = 0
    inversions = 0
    for sample in sorted(hot, key=lambda x: x["StartUS"]):
        while predecessor is not None and predecessor["EndUS"] < sample["StartUS"]:
            last_confirmed = max(last_confirmed, predecessor["Reply"].get("intakeSequence", 0))
            predecessor = next(finished, None)
        if last_confirmed and sample["Member"] in by_member:
            comparable += 1
            inversions += int(by_member[sample["Member"]]["intake_sequence"]) <= last_confirmed
    all_ack = {x["Member"] for x in ack} == expected and len(ack) == len(expected)
    correct = (
        all_ack
        and set(by_member) == expected
        and not bad
        and len(rows) == len(expected)
        and len(keys) == len(expected)
        and not sequence_gaps
        and inversions == 0
        and not duplicate_ids
        and not invalid_keys
        and not any(r["store_id"] == "1" for r in waiting)
    )
    pool_peaks = []
    for line in (folder / "observations.jsonl").read_text().splitlines():
        apps = json.loads(line).get("apps", [])
        pending = [a.get("hikaricp.connections.pending", {}).get("VALUE") for a in apps]
        if len(pending) == cfg["Apps"] and None not in pending:
            pool_peaks.append(sum(pending))
    slo = correct and delays["p95"] <= 1000 and delays["p99"] <= 2000
    expected_outcomes = {"availability": "http_200", "waiting": "http_201"}
    other_errors = sum(x["Outcome"] != expected_outcomes.get(x["Op"]) for x in other)
    report = {
        "target": cfg["Users"],
        "started_in_window": started,
        "wire_in_window": wire,
        "server_anytime": len(seen),
        "server_in_window": sum(any(begin <= a[0] * 1000 - origin < end for a in v) for v in seen),
        "ack202": len(ack),
        "db": len(rows),
        "ack_db_mismatch": bad,
        "db_without_ack": len(set(by_member) - {x["Member"] for x in ack}),
        "db_missing": len(expected - set(by_member)),
        "ack_ms": delays,
        "full_input_reconciled": correct,
        "duplicate_members": duplicate_members,
        "duplicate_receipt_ids": duplicate_ids,
        "sequence_gaps": sequence_gaps,
        "causally_comparable": comparable,
        "causal_order_violations": inversions,
        "invalid_keys": invalid_keys,
        "server_repeated_ids": sum(len(v) > 1 for v in seen),
        "completed_by_phase": dict(completed),
        "seconds_by_phase": {
            "before": cfg["Before"],
            "spike": cfg["Window"],
            "after": max(cfg["After"], (max(x["EndUS"] for x in samples) - end) / 1e6),
        },
        "candidate_latency_met": slo,
        "acks_within_1s": sum(t <= 1e6 for t in second_delays),
        "acks_within_2s": sum(t <= 2e6 for t in second_delays),
        "http_without_access": logged_missing,
        "other_input_complete": scheduled_other,
        "bad_read_count": len(bad_reads),
        "burst_202_per_second": sum(begin <= x["EndUS"] < end for x in ack) / cfg["Window"],
        "bg_201": len(bg_ack),
        "bg_db": len(bg),
        "bg_mismatches": bg_bad,
        "bg_errors": other_errors,
        "bg_recovery": other_windows(samples, cfg),
        "pool_pending_peak": max(pool_peaks, default=None),
        "outcomes": dict(Counter(x["Cohort"] + "/" + x["Op"] + "/" + classify(x) for x in samples)),
        "by_start_phase_ms": {k: distribution(v) for k, v in groups.items()},
    }
    report["verdict"] = (
        "insufficient"
        if min(started, wire) != cfg["Users"] or not scheduled_other or logged_missing
        else "pass"
        if slo and not other_errors and not bg_bad and not bad_reads and len(bg) == len(bg_ack)
        else "fail"
    )
    L.save(folder / "result.json", report)
    return report


def product_source():
    digest = hashlib.sha256()
    for path in sorted(p for p in (ROOT / "src/main").rglob("*") if p.is_file()):
        digest.update(str(path.relative_to(ROOT)).encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def trial(case, apps, variant=None):
    source = product_source()
    saved = (L.OUT / "measured-source.sha256").read_text().strip()
    if saved != source:
        raise RuntimeError(
            "Product source changed since the measured JAR was built; retain the completed trials and rebuild"
        )
    L.JAR = L.OUT / "measured.jar"
    lab = L.state()
    if L.digest(L.JAR) != L.digest(ROOT / "build/libs/catchtable-0.0.1-SNAPSHOT.jar"):
        raise RuntimeError("Measured JAR differs from the current build")
    day = L.sql(lab, L.ROLE, "SELECT current_date;")
    if (
        day != lab["fixture_date"]
        or L.sql(lab, L.ROLE, "SELECT localtime BETWEEN time '00:02' AND time '23:55';") != "t"
    ):
        raise RuntimeError("Fixture day or Seoul operating window changed")
    stamp = str(time.time_ns())
    db = "rb0_http" + stamp
    arm = f"{variant}-" if variant else ""
    folder = L.OUT / f"{stamp}-{arm}{case}-{apps}app"
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE};")
    n, window = CASES.get(case, (1, 1))
    date = str(dt.date.fromisoformat(day) + dt.timedelta(days=1))
    cfg = {
        "Flow": "waiting",
        "Users": n,
        "Window": window,
        "LaunchSpanMS": 990 if case == "open" else 1000 * window,
        "Apps": apps,
        "Date": date,
        "day": day,
        "Before": 4,
        "After": 60 if case == "open" else 20,
        "Background": 12,
        "db": db,
        "head": L.check(["git", "rev-parse", "HEAD"]),
        "jar_sha256": L.digest(L.JAR),
        "source_sha256": source,
        "client_sha256": L.digest(L.OUT / "client"),
    }
    if variant:
        cfg["variant"] = variant
        cfg["case"] = case
    L.save(folder / "config.json", cfg)
    print("START", folder.name, flush=True)
    with L.apps(lab, db, apps, folder) as (ports, ids):
        L.sql(
            lab,
            db,
            f"INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES (1,current_date,{n}),(3,current_date,{1 if case == 'smoke' else 100});",
        )
        if case == "smoke":
            smoke(ports, folder)
        else:
            warm(ports, date)
            if variant:
                L.save(
                    folder / "batch-before.json",
                    [L.api(p, "/actuator/metrics/waiting.intake.batch.requests") for p in ports],
                )
            run_client(lab, db, ports, ids, folder)
            if variant:
                L.save(
                    folder / "batch-after.json",
                    [L.api(p, "/actuator/metrics/waiting.intake.batch.requests") for p in ports],
                )
    for name, query in {
        "receipts": "SELECT r.*,r.xmin::text AS insert_xid FROM waiting_receipt r WHERE store_id=1 ORDER BY intake_sequence",
        "keys": "SELECT k.*,k.xmin::text AS insert_xid FROM waiting_receipt_key k JOIN waiting_receipt r USING(member_id,receipt_id) WHERE r.store_id=1",
        "waiting": "SELECT id,member_id,store_id,wait_number,status FROM waiting WHERE waiting_date=current_date AND store_id IN (1,2)",
    }.items():
        (folder / f"{name}.csv").write_text(
            L.sql(lab, db, f"COPY ({query}) TO STDOUT WITH CSV HEADER;") + "\n"
        )
    if case == "smoke":
        assert (
            L.sql(lab, db, "SELECT pending_count,last_sequence FROM waiting_intake_day WHERE store_id=1;")
            == "1|1"
        )
        assert L.sql(lab, db, "SELECT count(*) FROM waiting_receipt_key WHERE member_id=500;") == "2"
    else:
        r = summarize(folder)
        print(folder.name, r["verdict"], r["ack202"], r["db"], r["ack_ms"], flush=True)

    return folder


def replay_committed_timeouts(variant=None):
    """Retry acknowledged-by-PG, unseen-by-client receipts in an independent copy of each opening trial."""
    lab = L.state()
    L.JAR = L.OUT / "measured.jar"
    for original in sorted(L.OUT.glob("*-open-3app")):
        cfg = json.loads((original / "config.json").read_text())
        if variant and cfg.get("variant") != variant:
            continue
        if cfg["jar_sha256"] != L.digest(L.JAR) or cfg["db"] != "rb0_http" + original.name.split("-")[0]:
            raise RuntimeError("Source trial identity changed")
        with (original / "receipts.csv").open() as f:
            receipts = {int(r["member_id"]): r for r in csv.DictReader(f)}
        with (original / "client.jsonl").open() as f:
            lost = [
                s for line in f if (s := json.loads(line))["Cohort"] == "hot" and s["Outcome"] != "http_202"
            ]
        known = [s for s in lost if s["Member"] in receipts]
        stamp = str(time.time_ns())
        db = "rb0_retry" + stamp
        dest = L.OUT / (stamp + "-retry")
        dest.mkdir()
        L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {cfg['db']};")
        fingerprint = """SELECT row_to_json(r) FROM waiting_receipt r WHERE store_id=1 ORDER BY intake_sequence;
            SELECT row_to_json(k) FROM waiting_receipt_key k ORDER BY member_id,request_key;
            SELECT row_to_json(d) FROM waiting_intake_day d WHERE store_id=1;"""
        before = L.sql(lab, db, fingerprint)
        with L.apps(lab, db, 3, dest) as (ports, _):

            def recover(sample, receipts=receipts, day=cfg["day"]):
                m = sample["Member"]
                app = (sample["App"] + 1) % 3
                at = time.monotonic()
                try:
                    status, reply, location, hint = http(
                        ports[app], "POST", API, m, {"storeId": 1, "partyCount": 2}, f"intake-{m}"
                    )
                except (OSError, ValueError, client.HTTPException) as error:
                    status, reply, location, hint = 0, {"error": type(error).__name__}, None, None
                elapsed = (time.monotonic() - at) * 1000
                expected = receipts[m]
                match = (
                    status == 200
                    and reply.get("receiptId") == expected["receipt_id"]
                    and reply.get("intakeSequence") == int(expected["intake_sequence"])
                    and reply.get("receivedDate") == day
                    and reply.get("status") == "PENDING"
                    and location == API + "/receipts/" + expected["receipt_id"]
                    and hint == "2"
                )
                return [
                    m,
                    sample["App"],
                    app,
                    status,
                    reply.get("receiptId"),
                    reply.get("intakeSequence"),
                    match,
                    elapsed,
                ]

            start = time.monotonic()
            with concurrent.futures.ThreadPoolExecutor(max_workers=12) as clients:
                rows = list(clients.map(recover, known))
            seconds = time.monotonic() - start
        with (dest / "retries.csv").open("w", newline="") as f:
            writer = csv.writer(f, lineterminator="\n")
            writer.writerow(
                [
                    "member",
                    "original_app",
                    "new_app",
                    "http",
                    "receipt_id",
                    "sequence",
                    "matches",
                    "elapsed_ms",
                ]
            )
            writer.writerows(rows)
        unchanged = L.sql(lab, db, fingerprint) == before
        mismatches = sum(not r[-2] for r in rows)
        out = {
            "source": original.name,
            "db": db,
            "unseen_first_response": len(lost),
            "committed_retried": len(known),
            "unseen_without_db_row": len(lost) - len(known),
            "mismatches": mismatches,
            "ledger_unchanged": unchanged,
            "retry_ms": distribution([r[-1] for r in rows]),
            "wall_s": round(seconds, 3),
        }
        L.save(dest / "retry-result.json", out)
        print(dest.name, out, flush=True)
        if not unchanged or mismatches:
            raise RuntimeError("Replay mismatch; see retries.csv")


def export_results(destination):
    runs, latencies = [], []
    for version, root in (("initial", L.OUT.parent / "v1/runs"), ("final", L.OUT)):
        for folder in sorted(root.glob("*app")):
            if not (folder / "result.json").exists() or not (folder / "client.jsonl").exists():
                continue
            cfg = json.loads((folder / "config.json").read_text())
            r = summarize(folder)
            prefix = {
                "version": version,
                "run": folder.name,
                "case": folder.name.split("-")[1],
                "apps": cfg["Apps"],
            }
            before = json.loads((folder / "pool-before.json").read_text())
            after = json.loads((folder / "pool-after.json").read_text())
            acquire_seconds = None
            if all("TOTAL_TIME" in a.get("hikaricp.connections.acquire", {}) for a in before + after):
                acquire_seconds = round(
                    sum(
                        a["hikaricp.connections.acquire"]["TOTAL_TIME"]
                        - b["hikaricp.connections.acquire"]["TOTAL_TIME"]
                        for a, b in zip(after, before)
                    ),
                    3,
                )
            sqls = json.loads((folder / "statements.json").read_text())
            counter_sql = [q for q in sqls if q["query"].lstrip().upper().startswith("WITH ISSUED AS")]
            runs.append(
                {
                    **prefix,
                    **{
                        k: r[k]
                        for k in (
                            "verdict",
                            "target",
                            "started_in_window",
                            "wire_in_window",
                            "server_anytime",
                            "server_in_window",
                            "ack202",
                            "db",
                            "db_without_ack",
                            "db_missing",
                            "acks_within_1s",
                            "acks_within_2s",
                            "duplicate_members",
                            "duplicate_receipt_ids",
                            "sequence_gaps",
                            "causally_comparable",
                            "causal_order_violations",
                            "invalid_keys",
                            "candidate_latency_met",
                            "bg_201",
                            "bg_db",
                            "bg_errors",
                            "burst_202_per_second",
                            "pool_pending_peak",
                        )
                    },
                    "ack_db_mismatch": len(r["ack_db_mismatch"]),
                    "bg_mismatch": len(r["bg_mismatches"]),
                    "p50_ms": r["ack_ms"].get("p50"),
                    "p95_ms": r["ack_ms"].get("p95"),
                    "p99_ms": r["ack_ms"].get("p99"),
                    "bg_recovery_s": r["bg_recovery"]["two_windows_end_s"],
                    "pool_acquire_s_total": acquire_seconds,
                    "counter_sql_calls": sum(x["calls"] for x in counter_sql),
                    "counter_sql_total_ms": round(sum(x["total_exec_time"] for x in counter_sql), 2),
                }
            )
            groups = r["by_start_phase_ms"]
            for key in sorted(set(groups) | set(r["completed_by_phase"])):
                cohort, op, outcome, interval = key.split("/")
                g = groups.get(key, {})
                finished = r["completed_by_phase"].get(key, 0)
                latencies.append(
                    {
                        **prefix,
                        "cohort": cohort,
                        "op": op,
                        "outcome": outcome,
                        "phase": interval,
                        "n_started": g.get("n", 0),
                        "p50_ms": g.get("p50"),
                        "p95_ms": g.get("p95"),
                        "p99_ms": g.get("p99"),
                        "n_finished": finished,
                        "finished_per_s": round(finished / r["seconds_by_phase"][interval], 3),
                    }
                )
    destination.mkdir(parents=True, exist_ok=True)
    for name, rows in (("runs", runs), ("http", latencies)):
        with (destination / (name + ".csv")).open("w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=rows[0], lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
    gate = []
    for case, apps in [(c, 3) for c in CASES] + [("open", 1)]:
        trials = [r for r in runs if r["version"] == "final" and r["case"] == case and r["apps"] == apps]
        gate.append(
            {
                "case": case,
                "apps": apps,
                "trials": len(trials),
                "input_insufficient": sum(r["verdict"] == "insufficient" for r in trials),
                "passing": sum(
                    r["verdict"] == "pass" and r["bg_recovery_s"] is not None and r["bg_recovery_s"] <= 20
                    for r in trials
                ),
            }
        )
    L.save(
        destination / "intake-gate.json",
        {
            "all_candidate_checks_met": all(c["trials"] == 3 and c["passing"] == 3 for c in gate),
            "scope": "local intake HTTP comparison only",
            "conditions": gate,
        },
    )
    print(len(runs), "trials;", len(latencies), "latency rows")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("case", choices=["smoke", "report", "gate", "retry", *CASES])
    parser.add_argument("--apps", type=int, choices=(1, 3), default=3)
    parser.add_argument("--repeat", type=int, choices=(1, 2, 3), default=1)
    args = parser.parse_args()
    if args.case == "smoke" and args.apps != 3:
        parser.error("smoke requires three apps")
    if args.case in ("report", "gate"):
        destination = L.OUT.parent / "summary"
        export_results(destination)
        gate = json.loads((destination / "intake-gate.json").read_text())
        sys.exit(2 if args.case == "gate" and not gate["all_candidate_checks_met"] else 0)
    with L.lock():
        if args.case == "retry":
            replay_committed_timeouts()
            sys.exit(0)
        for _ in range(args.repeat):
            trial(args.case, args.apps)
