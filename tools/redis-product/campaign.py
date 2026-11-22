"""Same-JAR, private PG/Redis/Sentinel trials; keep first response, final visible number and DB evidence distinct."""

import argparse
import csv
import gzip
import json
import os
import subprocess
import threading
import time
from collections import Counter, defaultdict

import redis_lab as R
from check import http, check, until
from harness.observations import distribution, monitor, other_windows, pool, STATEMENTS

L = R.L
ROOT = L.ROOT
CASES = {
    "probe": (24, 2),
    "w1k": (1000, 10),
    "w5k": (5000, 10),
    "w10k": (10000, 10),
    "open": (10000, 1),
    "read": (1000, 10),
    "hold": (300, 2),
}


def csv_save(path, rows):
    if not rows:
        return
    with path.open("w", newline="") as h:
        writer = csv.DictWriter(h, fieldnames=rows[0], lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def setup(case, apps, arm, network):
    lab = L.state()
    stamp = str(time.time_ns())
    db = "rb0_full" + stamp
    folder = L.OUT / f"{stamp}-{arm}-{network}-{case}-{apps}app"
    folder.mkdir()
    day = L.sql(lab, L.ROLE, "SELECT current_date;")
    if day != lab["fixture_date"]:
        raise RuntimeError("Fixture Seoul date changed")
    if (
        L.sql(lab, L.ROLE, "SELECT localtime BETWEEN time '00:03' AND time '23:52';")
        != "t"
    ):
        raise RuntimeError("Outside dated fixture measurement window")
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE};")
    L.sql(lab, db, "CREATE EXTENSION IF NOT EXISTS pgrowlocks;")
    n, window = CASES[case]
    flow = {"read": "availability", "hold": "journey"}.get(case, "waiting")
    queue = arm in ("waiting", "both", "both-sync")
    cache = arm in ("cache", "both", "both-sync")
    async_http = (
        arm == "admit-async"
        or arm in ("waiting", "both")
        and os.getenv("CT_PRODUCT_ASYNC_CHOSEN", "true") == "true"
    )
    settings = R.settings(cache, queue, True, async_http)
    settings["diagnostics"] = {"tomcat-timing": True}
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(settings)
    os.environ["CT_TOMCAT_TIMING"] = "true"
    tomorrow = L.sql(lab, db, "SELECT current_date+1;")
    cfg = {
        "case": case,
        "arm": arm,
        "Network": network,
        "AppPrefix": lab["tag"] + "-" + db,
        "Apps": apps,
        "Users": n,
        "Window": window,
        "Flow": flow,
        "LaunchSpanMS": 990 if case == "open" else window * 1000,
        "Before": 4,
        "After": 32 if case == "open" else 16,
        "Background": 12,
        "Date": tomorrow,
        "day": day,
        "Follow": True if queue else False,
        "FinalLimit": 210,
        "db": db,
        "jar": L.digest(L.JAR),
        "client": L.digest(
            L.OUT / ("product-client" if network == "host" else "product-client-linux")
        ),
        "cache": cache,
        "dispatch": queue,
        "async": async_http,
        "async_http_limit_per_app": 120 // apps if async_http else 0,
    }
    cfg["campaign"] = os.environ.get("CT_PRODUCT_CAMPAIGN", "probe")
    L.save(folder / "config.json", cfg)
    return lab, db, folder, cfg


def warm(ports, cfg):
    for i in range(36):
        p = ports[i % len(ports)]
        check(
            http(
                p,
                "GET",
                f"/api/user/stores/{1 + i % 2}/availability?date={cfg['Date']}&partySize=2",
                4,
            )[0],
            200,
        )
        status = http(
            p,
            "POST",
            "/api/user/waitings",
            100 + i,
            {"storeId": 3, "partyCount": 2},
            f"warm-{i}",
        )[0]
        if status not in (201, 202):
            raise RuntimeError(f"Warmup returned {status}")
    for i in range(6):
        code, body, *_ = http(
            ports[i % len(ports)],
            "POST",
            "/api/user/reservations/holds",
            200 + i,
            {
                "storeId": 3,
                "partySize": 2,
                "reservationStartAt": cfg["Date"] + "T12:00:00+09:00",
            },
        )
        check(code, 201)
        check(
            http(
                ports[(i + 1) % len(ports)],
                "DELETE",
                "/api/user/reservations/holds/" + str(body["holdId"]),
                200 + i,
            )[0],
            204,
        )


def test(case, apps, arm, network="host"):
    lab, db, folder, cfg = setup(case, apps, arm, network)
    print("START", folder.name, flush=True)
    with L.apps(lab, db, apps, folder) as (ports, ids):
        if arm not in ("legacy", "cache"):
            activated = L.sql(
                lab,
                db,
                f"SELECT enable_waiting_intake(1,current_date,{cfg['Users']}),enable_waiting_intake(3,current_date,100);",
            )
            check(activated, "ENABLED|ENABLED")
        warm(ports, cfg)
        if cfg["dispatch"]:
            until(
                lambda: L.sql(
                    lab,
                    db,
                    "SELECT pending_count FROM waiting_intake_day WHERE store_id=3;",
                ),
                lambda n: n == "0",
                20,
            )
        until(
            lambda: L.sql(
                lab,
                db,
                "SELECT count(*) FROM waiting_sse_root WHERE status IN ('PENDING','CLAIMED');",
            ),
            lambda n: n == "0",
            30,
        )

        def stats():
            result = []
            for p in ports:
                item = pool(p)
                for metric in (
                    "availability.cache.hit",
                    "availability.cache.compute",
                    "availability.cache.join",
                    "availability.cache.busy",
                    "availability.cache.bypass",
                    "waiting.intake.batch.requests",
                ):
                    try:
                        c, body = L.api(p, "/actuator/metrics/" + metric, timeout=1)
                    except (OSError, ValueError) as error:
                        item[metric] = {"error": type(error).__name__}
                        continue
                    item[metric] = (
                        {x["statistic"]: x["value"] for x in body["measurements"]}
                        if c == 200
                        else {"http": c}
                    )
                result.append(item)
            return result

        L.save(folder / "before.json", stats())
        L.sql(lab, db, "SELECT pg_stat_statements_reset();")
        if network == "host":
            cmd = [
                str(L.OUT / "product-client"),
                str(folder / "config.json"),
                str(folder),
            ]
        else:
            cmd = [
                "docker",
                "--context",
                "desktop-linux",
                "run",
                "--rm",
                "--name",
                lab["tag"] + "-generator-" + db,
                "--label",
                "ct.product.generator=" + lab["tag"],
                "--network",
                lab["net"],
                "--cpus",
                "3",
                "--memory",
                "768m",
                "-e",
                "GOMAXPROCS=3",
                "-v",
                str(L.OUT / "product-client-linux") + ":/client:ro",
                "-v",
                str(folder) + ":/data",
                "--entrypoint",
                "/client",
                lab["jre_image"],
                "/data/config.json",
                "/data",
            ]
        with (
            (folder / "client.log").open("x") as log,
            (folder / "containers.log").open("x") as cout,
        ):
            containers = subprocess.Popen(
                [
                    "docker",
                    "--context",
                    "desktop-linux",
                    "stats",
                    "--format",
                    "{{json .}}",
                    lab["id"],
                    *ids,
                ],
                stdout=cout,
                stderr=subprocess.STDOUT,
            )
            end = threading.Event()
            try:
                with subprocess.Popen(
                    cmd,
                    env=dict(os.environ, GOMAXPROCS="3"),
                    stdout=log,
                    stderr=subprocess.STDOUT,
                ) as proc:
                    watch = threading.Thread(
                        target=monitor, args=(lab, db, ports, ids, folder, proc, end)
                    )
                    watch.start()
                    try:
                        if proc.wait(timeout=320) != 0:
                            raise RuntimeError(f"Client failed: {folder}/client.log")
                    finally:
                        end.set()
                        watch.join()
            finally:
                containers.terminate()
                containers.wait(timeout=10)
        L.save(folder / "after.json", stats())
        (folder / "statements.json").write_text(L.sql(lab, db, STATEMENTS) + "\n")
        if cfg["dispatch"]:
            # Keep observing even after a client deadline or lost connection. Do not equate a client timeout with rollback.
            drain = []
            for _ in range(120):
                pending = int(
                    L.sql(
                        lab,
                        db,
                        "SELECT pending_count FROM waiting_intake_day WHERE store_id=1;",
                    )
                )
                drain.append([time.time(), pending])
                if not pending:
                    break
                time.sleep(1)
            L.save(folder / "drain.json", drain)
    for name, sql in {
        "ledger": """SELECT r.member_id,r.receipt_id,r.intake_sequence,r.status,r.waiting_id,r.received_date,r.xmin::text AS last_xid,
                w.wait_number,w.member_id actual_member,w.waiting_date,w.status actual_status,k.request_key
                FROM waiting_receipt r LEFT JOIN waiting w ON w.id=r.waiting_id
                LEFT JOIN waiting_receipt_key k ON (k.member_id,k.receipt_id)=(r.member_id,r.receipt_id)
                WHERE r.store_id=1 ORDER BY r.intake_sequence""",
        "waitings": "SELECT id,member_id,store_id,wait_number,status FROM waiting WHERE waiting_date=current_date AND store_id IN (1,2) ORDER BY store_id,wait_number",
        "holds": "SELECT id,member_id,store_id,table_id,status FROM reservation WHERE store_id=1 AND hold_expires_at IS NOT NULL ORDER BY id",
        "permits": "SELECT id FROM waiting_registration_permit ORDER BY id",
    }.items():
        (folder / (name + ".csv")).write_text(
            L.sql(lab, db, f"COPY ({sql}) TO STDOUT WITH CSV HEADER;") + "\n"
        )
    result = analyze(folder)
    print(
        "END",
        folder.name,
        result["verdict"],
        result["wire"],
        result["first_success"],
        result["first_ms"],
        result["final_observed"],
        result["final_ms"],
        flush=True,
    )
    return folder


def read_rows(f):
    with f.open() as h:
        return list(csv.DictReader(h))


def analyze(folder):
    c = json.loads((folder / "config.json").read_text())
    begin = c["Before"] * 1e6
    end = begin + c["Window"] * 1e6

    def phase(t):
        return "before" if t < begin else "spike" if t < end else "after"

    origin = int((folder / "origin-us.txt").read_text())
    access = {}
    server_repeats = 0
    for f in folder.glob("access-*/*.log"):
        for l in f.open():
            v = l.split()
            if (
                len(v) >= 7
                and v[2].startswith("hot-")
                and v[2].rsplit("-", 1)[-1] in ("waiting", "availability", "hold")
            ):
                if v[2] in access:
                    server_repeats += 1
                access[v[2]] = v
    ledger = read_rows(folder / "ledger.csv")
    by = {int(row["member_id"]): row for row in ledger}
    ws = read_rows(folder / "waitings.csv")
    hotwait = {int(row["member_id"]): row for row in ws if row["store_id"] == "1"}
    otherwait = {int(row["member_id"]): row for row in ws if row["store_id"] == "2"}
    holds = {int(row["member_id"]): row for row in read_rows(folder / "holds.csv")}
    initial = {}
    first_ok = {}
    final = {}
    bg = []
    bad = []
    hold_acks = set()
    http_n = 0
    lat = defaultdict(list)
    completed = Counter()
    timing = defaultdict(list)
    out = Counter()
    polls = Counter()
    with gzip.open(folder / "client.jsonl.gz", "rt") as f:
        for l in f:
            s = json.loads(l)
            http_n += 1
            member = s["Member"]
            op = s["Op"]
            o = s["Outcome"]
            rep = s["Reply"]
            if o == "transport":
                err = s.get("Error", "").lower()
                o = next(
                    (
                        k
                        for k, token in (
                            ("timeout", "deadline"),
                            ("timeout", "timeout"),
                            ("reset", "reset"),
                            ("eof", "eof"),
                            ("refused", "refused"),
                        )
                        if token in err
                    ),
                    "transport_other",
                )
            key = (s["Cohort"], op, o)
            out["/".join(key)] += 1
            lat[key + (phase(s["StartUS"]),)].append((s["EndUS"] - s["StartUS"]) / 1000)
            completed[key + (phase(s["EndUS"]),)] += 1
            if s["Cohort"] == "other":
                bg.append(s)
                if (
                    op == "waiting"
                    and s["Status"] == 201
                    and (
                        member not in otherwait
                        or rep.get("waitingId") != int(otherwait[member]["id"])
                    )
                ):
                    bad.append([member, "B waiting"])
                continue
            if op in ("waiting", "availability"):
                initial[member] = s
                if s["Status"] in (200, 201, 202) and o.startswith("http_"):
                    first_ok[member] = s
                    a = access.get(s["ID"])
                    if a and a[5] == str(s["Status"]):
                        timing["write_to_access_begin"].append(
                            int(a[0]) - (origin + s["WrittenUS"]) / 1000
                        )
                        timing["access_duration"].append(int(a[1]) - int(a[0]))
                        timing["client_connect"].append(
                            (s["GotConnUS"] - s["StartUS"]) / 1000
                        )
                        if len(a) == 10 and a[7] != "-":
                            timing["write_to_executor_submit"].append(
                                int(a[7]) - (origin + s["WrittenUS"]) / 1000
                            )
                            timing["executor_queue"].append(int(a[9]) / 1000)
                if (
                    op == "availability"
                    and s["Status"] == 200
                    and (
                        s["Slots"] != 44
                        or c["Flow"] == "availability"
                        and s["Available"] != 12
                    )
                ):
                    bad.append([member, "A slots"])
            if op == "hold" and s["Status"] == 201:
                hold_acks.add(member)
                if (
                    member not in holds
                    or int(holds[member]["table_id"]) != rep.get("tableId")
                    or int(holds[member]["id"]) != rep.get("holdId")
                ):
                    bad.append([member, "hold allocation"])
            if op == "poll":
                polls[member] += 1
            if "receiptId" in rep and s["Status"] in (200, 201, 202):
                row = by.get(member)
                if (
                    not row
                    or rep["receiptId"] != row["receipt_id"]
                    or rep.get("intakeSequence") != int(row["intake_sequence"])
                    or rep.get("receivedDate") != c["day"]
                ):
                    bad.append([member, "receipt"])
            if (
                op in ("waiting", "retry", "poll")
                and rep.get("waitingId")
                and s["Status"] in (200, 201)
            ):
                row = hotwait.get(member)
                if (
                    not row
                    or int(row["id"]) != rep["waitingId"]
                    or int(row["wait_number"]) != rep.get("waitNumber")
                ):
                    bad.append([member, "number"])
                final[member] = (s["EndUS"] - s["JourneyStartUS"]) / 1000
    numbers = [int(r["wait_number"]) for r in ledger if r["wait_number"]]
    reverse = sum(a >= b for a, b in zip(numbers, numbers[1:]))
    actual_bad = [
        r["member_id"]
        for r in ledger
        if r["waiting_id"]
        and (
            r["actual_member"] != r["member_id"]
            or r["waiting_date"] != r["received_date"]
        )
    ]
    key_bad = sum(row["request_key"] != "intake-" + row["member_id"] for row in ledger)
    eq = set(range(1000, 1000 + c["Users"]))
    fp = distribution([(s["EndUS"] - s["StartUS"]) / 1000 for s in first_ok.values()])
    terminal = distribution(list(final.values()))
    wire = sum(begin <= s["WrittenUS"] < end for s in initial.values())
    starts = sum(begin <= s["StartUS"] < end for s in initial.values())
    expected_bg = {"availability": "http_200", "waiting": "http_201"}
    bg_err = sum(s["Outcome"] != expected_bg[s["Op"]] for s in bg)
    duration_after = max((max(s["StartUS"] for s in bg) - end) / 1e6, 0)
    recovery = other_windows(bg, dict(c, After=int(duration_after + 0.1)))
    metrics = {}
    sql = json.loads((folder / "statements.json").read_text())
    for label, match in [
        ("slot_count", "select count(st.id) from (values"),
        ("batch_rows", "with incoming as"),
        ("waiting_insert", "insert into waiting ("),
    ]:
        stat = [x for x in sql if match in " ".join(x["query"].lower().split())]
        metrics[label] = {
            "calls": sum(x["calls"] for x in stat),
            "exec_ms": round(sum(x["total_exec_time"] for x in stat), 2),
        }
    b, a = [
        json.loads((folder / (s + ".json")).read_text()) for s in ("before", "after")
    ]
    for k in (
        "availability.cache.hit",
        "availability.cache.compute",
        "availability.cache.join",
        "availability.cache.bypass",
        "hikaricp.connections.timeout",
    ):
        measurements = [row.get(k, {}) for row in a + b]
        if all("COUNT" in m or m.get("http") == 404 for m in measurements):
            metrics[k] = sum(
                x[k].get("COUNT", 0) - y[k].get("COUNT", 0) for x, y in zip(a, b)
            )
        else:
            metrics[k] = None
    other_id_ok = {s["Member"] for s in bg} == set(range(18000, 18000 + len(bg)))
    missing_access = sum(s["ID"] not in access for s in first_ok.values())
    unexpected_hold = sum(
        n
        for k, n in out.items()
        if k.startswith("hot/hold/")
        and k not in ("hot/hold/http_201", "hot/hold/http_409")
    )
    integrity = not (
        bad
        or len(ws) != len(hotwait) + len(otherwait)
        or unexpected_hold
        or reverse
        or actual_bad
        or key_bad
        or len({r["receipt_id"] for r in ledger}) != len(ledger)
        or len(by) != len(ledger)
        or len(set(numbers)) != len(numbers)
        or server_repeats
    )
    if ledger:
        integrity &= [int(r["intake_sequence"]) for r in ledger] == list(
            range(1, len(ledger) + 1)
        )
    accepted = first_ok.keys() == eq
    if c["arm"] not in ("legacy", "cache") and c["Flow"] == "waiting":
        accepted &= by.keys() == eq
    if c["Follow"]:
        integrity &= {row["id"] for row in hotwait.values()} == {
            row["waiting_id"] for row in ledger if row["waiting_id"]
        }
        accepted &= final.keys() == eq and len(numbers) == c["Users"]
    if c["Flow"] == "journey":
        integrity &= (
            len(holds) == 12
            and hold_acks == holds.keys()
            and len({r["table_id"] for r in holds.values()}) == 12
        )
    else:
        integrity &= not holds
    if c["Flow"] == "waiting" and c["arm"] == "legacy":
        accepted &= hotwait.keys() == eq
    goals = (
        fp.get("p95", float("inf")) <= 1000
        and fp.get("p99", float("inf")) <= 2000
        and (
            not c["Follow"]
            or terminal.get("p95", float("inf")) <= 60000
            and terminal.get("p99", float("inf")) <= 120000
        )
    )
    heap_errors = sum(
        "OutOfMemoryError" in f.read_text(errors="replace")
        for f in folder.glob("app-*.log")
    )
    healthy = (
        not heap_errors
        and integrity
        and accepted
        and goals
        and not bg_err
        and recovery["two_windows_end_s"] is not None
        and recovery["two_windows_end_s"] <= 20
    )
    r = {
        "case": c["case"],
        "arm": c["arm"],
        "apps": c["Apps"],
        "network": c["Network"],
        "target": c["Users"],
        "started": starts,
        "wire": wire,
        "first_success": len(first_ok),
        "first_ms": fp,
        "final_observed": len(final),
        "final_ms": terminal,
        "db_receipts": len(ledger),
        "db_waiting": len(hotwait),
        "fifo_inversions": reverse,
        "counter_or_member_errors": len(actual_bad) + key_bad,
        "response_mismatches": bad,
        "integrity": bool(integrity),
        "apps_with_heap_errors": heap_errors,
        "all_accepted": bool(accepted),
        "candidate_latencies": goals,
        "B_errors": bg_err,
        "B_recovery_s": recovery["two_windows_end_s"],
        "B_recovery": recovery,
        "http_count": http_n,
        "outcomes": dict(out),
        "poll_count": sum(polls.values()),
        "polls_per_user": distribution(list(polls.values())),
        "timing_ms": {k: distribution(v) for k, v in timing.items()},
        "db": metrics,
        "phase_s": {
            "before": c["Before"],
            "spike": c["Window"],
            "after": max(0.001, duration_after + 1 / c["Background"]),
        },
        "phase_ms": {"/".join(k): distribution(v) for k, v in lat.items()},
        "phase_finished": {"/".join(k): n for k, n in completed.items()},
        "access_missing_success": missing_access,
        "other_starts_complete": other_id_ok,
        "verdict": "insufficient"
        if missing_access
        or not other_id_ok
        or starts != c["Users"]
        or wire != c["Users"]
        else "pass"
        if healthy
        else "fail",
    }
    L.save(folder / "result.json", r)
    return r


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("case", choices=CASES)
    p.add_argument("--apps", type=int, choices=(1, 3), default=3)
    p.add_argument(
        "--arm",
        choices=(
            "admit-sync",
            "admit-async",
            "legacy",
            "cache",
            "waiting",
            "both",
            "both-sync",
        ),
        default="both",
    )
    p.add_argument(
        "--network", choices=("host", "bridge", "published-from-linux"), default="host"
    )
    a = p.parse_args()
    with L.lock():
        test(a.case, a.apps, a.arm, a.network)
