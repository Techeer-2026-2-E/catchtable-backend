"""Exercise the mixed-route DB cap with two customers, one PG transaction and three real JVMs."""

import json
import os
import time
from concurrent.futures import ThreadPoolExecutor

import redis_lab as topology
from check import check, http, post, receipt, until
from harness.observations import sql_session

L = topology.L


def verify():
    lab = L.state()
    stamp = str(time.time_ns())
    db = "rb0_product" + stamp
    folder = L.OUT / (stamp + "-registration-slots")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE}")
    # Select the current product artifact even when earlier trials used a different measured.jar.
    L.JAR = L.ROOT / "build/libs/catchtable-0.0.1-SNAPSHOT.jar"
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(
        topology.settings(True, True, True, True)
    )
    with L.apps(lab, db, 3, folder) as (ports, _):
        check(
            L.sql(
                lab,
                db,
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
            ),
            "49",
        )
        L.sql(
            lab,
            db,
            "INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES(1,current_date,3)",
        )
        with (
            ThreadPoolExecutor(max_workers=1) as calls,
            sql_session(lab, db, begin=True) as hold,
        ):
            hold("SELECT id FROM waiting_registration_permit ORDER BY id FOR UPDATE;")
            accepted, body, location, _ = post(ports[0], 1000)
            check(accepted, 202)
            legacy = calls.submit(post, ports[1], 1001, 2)
            blocker_sql = """SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                             AND cardinality(pg_blocking_pids(pid))>0 AND query LIKE '%take_waiting_registration_slot(true)%'"""
            until(lambda: int(L.sql(lab, db, blocker_sql)), lambda n: n > 0, 6)
            check(
                L.sql(
                    lab,
                    db,
                    "SELECT count(*) FROM waiting WHERE member_id IN (1000,1001)",
                ),
                "0",
            )
            hold("COMMIT;")
            legacy_status, legacy_row, *_ = legacy.result(timeout=10)
            check(legacy_status, 201)
        actual = until(
            lambda: receipt(ports[2], 1000, location),
            lambda r: r["status"] == "REGISTERED",
        )
        check(actual["waitNumber"], 1)
        check(legacy_row["waitNumber"], 1)
        retry, same, *_ = post(ports[1], 1000)
        check(retry, 200)
        check(
            (body["receiptId"], actual["waitingId"]),
            (same["receiptId"], same["waitingId"]),
        )
        cancel, withdrawn, *_ = http(ports[2], "DELETE", location, 1000)
        check((cancel, withdrawn["status"]), (200, "CANCELED"))
        target = L.sql(lab, db, "SELECT current_date+1")
        url = f"/api/user/stores/1/availability?date={target}&partySize=2"
        for port in (*ports, *ports):
            code, slots, *_ = http(port, "GET", url, 1000)
            check(code, 200)
            assert slots["slots"]
        hits = sum(
            next(
                (
                    m["value"]
                    for m in http(
                        port, "GET", "/actuator/metrics/availability.cache.hit", 1000
                    )[1].get("measurements", [])
                ),
                0,
            )
            for port in ports
        )
        assert hits > 0
        results = {
            "jar": L.digest(L.JAR),
            "flyway": 49,
            "apps": 3,
            "customers": 2,
            "while_slots_held": {
                "receipt_http": accepted,
                "legacy_blocked_in_pg": True,
                "registrations": 0,
            },
            "after_release": {
                "A_receipt_number": actual["waitNumber"],
                "B_legacy_number": legacy_row["waitNumber"],
                "replay": retry,
                "cancel": withdrawn["status"],
                "shared_cache_hits": hits,
            },
        }
        L.save(folder / "slots.json", results)
        print(folder.name, json.dumps(results), flush=True)


if __name__ == "__main__":
    with L.lock():
        verify()
