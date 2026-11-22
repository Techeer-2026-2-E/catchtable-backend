"""Product contracts on real HTTP/PG/Redis. All SQL, Redis power changes and clients are bound to the owned lab."""

import datetime as dt
import http.client
import json
import os
import time

import redis_lab as topology

L = topology.L
from harness.observations import http, API, sql_session


def check(value, expected):
    if value != expected:
        raise AssertionError(f"Expected {expected!r}, observed {value!r}")
    return value


def until(function, predicate, timeout=20):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            last = function()
            if predicate(last):
                return last
        except (OSError, ValueError) as error:
            last = str(error)
        time.sleep(0.05)
    raise RuntimeError(f"Timed out, last observation: {last!r}")


def post(port, member, store=1, party=2, key=None):
    return http(
        port,
        "POST",
        API,
        member,
        {"storeId": store, "partyCount": party},
        key or f"apply-{member}",
    )


def receipt(port, member, location):
    status, body, *_ = http(port, "GET", location, member)
    check(status, 200)
    return body


def actual_master():
    from collections import Counter
    import subprocess

    r = topology.runtime()
    votes = Counter()
    for sentinel in ("s1", "s2", "s3"):
        try:
            address = topology.cli(
                sentinel, "SENTINEL", "get-master-addr-by-name", "ct-waiting"
            ).splitlines()
            if len(address) == 2:
                votes[address[0]] += 1
        except subprocess.CalledProcessError:
            pass
    if not votes:
        return None
    host, n = votes.most_common(1)[0]
    if n < 2:
        return None
    for node in ("master", "replica"):
        alias = r["names"][node]
        ip = L.docker(
            "inspect",
            "--format",
            "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
            r["nodes"][node],
        )
        if host not in (alias, ip):
            continue
        try:
            if "role:master" not in topology.cli(node, "INFO", "replication"):
                return None
            other = "replica" if node == "master" else "master"
            if (
                L.docker("inspect", "--format", "{{.State.Running}}", r["nodes"][other])
                == "true"
            ):
                repl = topology.cli(other, "INFO", "replication")
                if "role:slave" not in repl or "master_link_status:up" not in repl:
                    return None
            return node
        except subprocess.CalledProcessError:
            return None
    return None


def reconcile_cancellations(lab, db, folder, race):
    import csv
    import io

    raw = L.sql(
        lab,
        db,
        """COPY (
        SELECT r.member_id, r.receipt_id, r.intake_sequence, r.status, r.waiting_id,
               c.outcome, w.wait_number, w.status AS restaurant_status
        FROM waiting_receipt_cancel c JOIN waiting_receipt r USING (member_id,receipt_id)
        LEFT JOIN waiting w ON w.id=r.waiting_id ORDER BY r.member_id
    ) TO STDOUT WITH CSV HEADER""",
    )
    (folder / "cancellations.csv").write_text(raw + "\n")
    rows = {int(r["member_id"]): r for r in csv.DictReader(io.StringIO(raw))}
    check(set(rows), {1000, 1011, 1050})
    for member, expected in (
        (1000, "CANCELED"),
        (1011, "WITHDRAWN"),
        (1050, race["status"]),
    ):
        row = rows[member]
        check((row["status"], row["outcome"]), (expected, expected))
        if expected == "WITHDRAWN":
            check((row["waiting_id"], row["wait_number"]), ("", ""))
        else:
            check(row["restaurant_status"], "CANCELED")
            assert row["waiting_id"] and row["wait_number"]
    check(rows[1050]["receipt_id"], race["receiptId"])
    return {"checked": len(rows), "mismatches": 0}


def check_product():
    lab = L.state()
    stamp = str(time.time_ns())
    db = "rb0_product" + stamp
    folder = L.OUT / (stamp + "-product")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE};")
    settings = topology.settings()
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(settings)
    day = L.sql(lab, db, "SELECT current_date;")
    target = str(dt.date.fromisoformat(day) + dt.timedelta(days=1))
    log = {"db": db, "day": day, "jar": L.digest(L.JAR)}
    L.save(folder / "checkpoints.json", log)

    def save(k, v):
        log[k] = v
        L.save(folder / "checkpoints.json", log)
        print(k, str(v)[:180], flush=True)

    with L.apps(lab, db, 3, folder) as (ports, _):
        L.sql(
            lab,
            db,
            "INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES(1,current_date,4);",
        )
        until(
            lambda: http(ports[0], "GET", "/actuator/health", 4)[0], lambda s: s == 200
        )
        check(post(ports[1], 16000, store=2)[0], 201)
        check(post(ports[2], 16000, store=2)[0], 200)
        code, first, location, h = post(ports[0], 1000)
        check(code in (201, 202), True)
        reg = until(
            lambda: receipt(ports[1], 1000, location),
            lambda b: b["status"] == "REGISTERED",
        )
        check(reg["waitNumber"], 1)
        check(reg["teamsAhead"], 0)
        check(post(ports[2], 1000)[1]["receiptId"], first["receiptId"])
        check(post(ports[2], 1000, key="alias")[1]["receiptId"], first["receiptId"])
        check(post(ports[2], 1000, party=3)[0], 409)
        check(http(ports[1], "GET", location, 1001)[0], 404)
        save(
            "idle",
            {"http": code, "actual": reg["waitNumber"], "receipt": reg["receiptId"]},
        )

        def slots(p):
            s, r, *_ = http(
                p,
                "GET",
                f"/api/user/stores/1/availability?date={target}&partySize=2",
                4,
            )
            check(s, 200)
            return next(
                x["availableTableCount"]
                for x in r["slots"]
                if x["reservationStartAt"] == target + "T12:00:00+09:00"
            )

        for p in ports:
            check(slots(p), 12)
        check(slots(ports[0]), 12)
        code, hold, *_ = http(
            ports[0],
            "POST",
            "/api/user/reservations/holds",
            17000,
            {
                "storeId": 1,
                "partySize": 2,
                "reservationStartAt": target + "T12:00:00+09:00",
            },
        )
        check(code, 201)
        until(lambda: slots(ports[2]), lambda n: n == 11, 3)
        check(
            http(
                ports[0],
                "DELETE",
                "/api/user/reservations/holds/" + str(hold["holdId"]),
                17000,
            )[0],
            204,
        )
        until(lambda: slots(ports[1]), lambda n: n == 12, 3)
        hit = sum(
            http(p, "GET", "/actuator/metrics/availability.cache.hit", 4)[1].get(
                "measurements", [{"value": 0}]
            )[0]["value"]
            for p in ports
        )
        assert hit > 0
        save(
            "cache", {"shared_read_hits": hit, "before": 12, "hold": 11, "released": 12}
        )
        topology.power("cache", False)
        try:
            check(slots(ports[0]), 12)
            code, other, loc, _ = post(ports[2], 1001)
            reg2 = until(
                lambda: receipt(ports[1], 1001, loc),
                lambda b: b["status"] == "REGISTERED",
            )
            check(reg2["waitNumber"], 2)
            save(
                "cache_down", {"db_bypass": True, "queue_continues": reg2["waitNumber"]}
            )
        finally:
            topology.power("cache", True)

        old = until(actual_master, lambda n: n is not None, 40)
        check(old in ("master", "replica"), True)
        follower = "replica" if old == "master" else "master"
        topology.power(follower, False)
        topology.power(old, False)
        try:
            # Both queue data nodes are unavailable; PG is still authoritative.
            enrolled = {}
            for m in range(1010, 1014):
                code, r, loc, _ = post(ports[m % 3], m)
                check(code, 202)
                enrolled[m] = (r, loc)
            check(post(ports[0], 1020)[0], 429)
            m = 1011
            code, r, *_ = http(ports[0], "DELETE", enrolled[m][1], m)
            check([code, r["status"]], [200, "WITHDRAWN"])
            check(http(ports[2], "DELETE", enrolled[m][1], m)[1]["status"], "WITHDRAWN")
            code, r, loc, _ = post(ports[2], 1020)
            check(code, 202)
            enrolled[1020] = (r, loc)
            check(http(ports[0], "DELETE", location, 1000)[1]["status"], "CANCELED")
            check(post(ports[0], 1010)[1]["receiptId"], enrolled[1010][0]["receiptId"])
            check(L.sql(lab, db, "SELECT retire_waiting_intake(1,current_date);"), "f")
            save(
                "queue_down",
                {
                    "accepted": 5,
                    "full": 429,
                    "pending_withdrawal": True,
                    "registered_cancel": True,
                },
            )
        finally:
            topology.power(old, True)
            topology.power(follower, True)

        # Fence the registrar after it has durably claimed but before its store-locked registration.
        # If the previous restart has already drained, use newly accepted work with all global permits held.
        for m in (1010, 1012, 1013, 1020):
            until(
                lambda m=m: receipt(ports[1], m, enrolled[m][1]),
                lambda b: b["status"] == "REGISTERED",
                40,
            )
        numbers = [
            receipt(ports[0], m, enrolled[m][1])["waitNumber"]
            for m in (1010, 1012, 1013, 1020)
        ]
        check(numbers, [3, 4, 5, 6])
        save("recovery", {"fifo_numbers": numbers})

        # Pause drivers using a store lock only after durable admission, by briefly disconnecting the queue.
        old = until(actual_master, lambda n: n is not None, 40)
        follower = "replica" if old == "master" else "master"
        topology.power(follower, False)
        topology.power(old, False)
        try:
            code, r, loc, _ = post(ports[2], 1050)
            check(code, 202)
            with sql_session(lab, db, begin=True) as lock:
                lock("SELECT id FROM store WHERE id=1 FOR UPDATE;")
                topology.power(old, True)
                topology.power(follower, True)
                until(
                    lambda: receipt(ports[1], 1050, loc),
                    lambda b: b["status"] == "PROCESSING",
                    30,
                )
                until(
                    lambda: L.sql(
                        lab,
                        db,
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0 AND query LIKE '%from store %';",
                    ),
                    lambda n: int(n) > 0,
                    12,
                )
                code, r, *_ = http(ports[2], "DELETE", loc, 1050)
                check([code, r["status"]], [202, "CANCEL_REQUESTED"])
                lock("COMMIT;")
            terminal = until(
                lambda: receipt(ports[0], 1050, loc),
                lambda b: b["status"] in ("WITHDRAWN", "CANCELED"),
            )
            save("cancellation_race", terminal)
        finally:
            for n in (old, follower):
                if (
                    L.docker(
                        "inspect",
                        "--format",
                        "{{.State.Running}}",
                        topology.runtime()["nodes"][n],
                    )
                    == "false"
                ):
                    topology.power(n, True)

        old = until(actual_master, lambda n: n is not None, 40)
        follower = "replica" if old == "master" else "master"
        until(
            lambda: topology.cli(follower, "INFO", "replication"),
            lambda s: "master_link_status:up" in s,
        )
        topology.power(old, False)
        try:
            until(lambda: actual_master(), lambda n: n == follower, 35)
            code, r, loc, _ = post(ports[0], 1060)
            check(code in (201, 202), True)
            final = until(
                lambda: receipt(ports[2], 1060, loc),
                lambda b: b["status"] == "REGISTERED",
                35,
            )
            save(
                "sentinel",
                {"old": old, "promoted": follower, "waitNumber": final["waitNumber"]},
            )
        finally:
            topology.power(old, True)

        check(L.sql(lab, db, "SELECT retire_waiting_intake(1,current_date);"), "t")
        check(post(ports[1], 1070)[0], 201)
        check(post(ports[1], 1010)[1]["receiptId"], enrolled[1010][0]["receiptId"])
        check(receipt(ports[1], 1000, location)["status"], "CANCELED")
        save("rollback", {"legacy_new": 201, "old_receipt_readable": True})
        # Restart both actual queue processes from their AOF files. The new PG receipt has never been visible to Redis.
        master = until(actual_master, lambda n: n is not None, 40)
        follower = "replica" if master == "master" else "master"
        until(
            lambda: topology.cli(follower, "INFO", "replication"),
            lambda x: "master_link_status:up" in x,
        )
        for n in (master, follower):
            check(
                topology.cli(n, "CONFIG", "GET", "appendonly").splitlines()[-1], "yes"
            )
            check(
                topology.cli(n, "CONFIG", "GET", "appendfsync").splitlines()[-1],
                "everysec",
            )
        mark = "ct:product:test:aof:" + stamp
        topology.cli(master, "SET", mark, "durable")
        until(lambda: topology.cli(follower, "GET", mark), lambda x: x == "durable")
        time.sleep(1.3)
        L.sql(
            lab,
            db,
            "INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES(3,current_date,2);",
        )
        nodes = topology.runtime()
        try:
            for n in ("s1", "s2", "s3"):
                topology.power(n, False)
            for n in (master, follower):
                cid = nodes["nodes"][n]
                topology.verify(cid, nodes["owner"])
                L.docker("kill", "--signal", "KILL", cid)
            code, receipt3, loc3, _ = post(ports[0], 1200, store=3)
            check(code, 202)
        finally:
            for n in (master, follower, "s1", "s2", "s3"):
                topology.power(n, True)
        until(lambda: actual_master(), lambda x: x in ("master", "replica"), 30)
        until(
            lambda: topology.cli(actual_master(), "GET", mark),
            lambda x: x == "durable",
            25,
        )
        restored = until(
            lambda: receipt(ports[2], 1200, loc3),
            lambda x: x["status"] == "REGISTERED",
            35,
        )
        check(
            [restored["receiptId"], restored["waitNumber"]], [receipt3["receiptId"], 1]
        )
        save(
            "aof_restart",
            {
                "both_processes_killed": True,
                "everysec_marker_replayed": True,
                "missing_lane_rediscovered": True,
            },
        )
        check(
            L.sql(
                lab,
                db,
                "SELECT pending_count FROM waiting_intake_day WHERE store_id=1;",
            ),
            "0",
        )
        check(
            L.sql(
                lab,
                db,
                "SELECT count(*) FROM waiting_receipt_cancel WHERE outcome IS NULL;",
            ),
            "0",
        )
    save(
        "cancel_reconciliation",
        reconcile_cancellations(lab, db, folder, log["cancellation_race"]),
    )
    print(folder, flush=True)


if __name__ == "__main__":
    with L.lock():
        check_product()
