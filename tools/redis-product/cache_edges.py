"""Live cache boundaries, three-instance singleflight and pre-pool failover checks."""

import concurrent.futures
import json
import os
import time
import redis_lab as R
from check import check, until, http
from harness.observations import sql_session

L = R.L


def run():
    lab = L.state()
    stamp = str(time.time_ns())
    db = "rb0_product" + stamp
    folder = L.OUT / (stamp + "-cache-edges")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE};")
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(R.settings(cache=True, queue=False))
    date = L.sql(lab, db, "SELECT current_date+1;")
    out = {"db": db, "date": date, "jar": L.digest(L.JAR)}
    base = "ct:availability:s:1:"

    def scan(pat):
        return [x for x in R.cli("cache", "--scan", "--pattern", pat).splitlines() if x]

    with L.apps(lab, db, 3, folder) as (ports, _):

        def get(i, party=2):
            code, body, _, hint = http(
                ports[i],
                "GET",
                f"/api/user/stores/1/availability?date={date}&partySize={party}",
                1010,
            )
            return code, len(body.get("slots", [])), hint

        for i in range(3):
            check(get(i)[:2], (200, 44))

        def physical(party=2):
            parts = [
                R.cli("cache", "GET", k)
                for k in ("ct:availability:namespace", base + "*", base + date)
            ]
            return base + date + ":" + str(party) + ":" + ":".join(parts)

        k = physical()
        check(R.cli("cache", "EXISTS", k), "1")
        ttl1 = int(R.cli("cache", "PTTL", k))
        for i in range(3):
            get(i)
        ttl2 = int(R.cli("cache", "PTTL", k))
        assert 0 < ttl2 < ttl1 <= 2000, (ttl1, ttl2)
        R.cli("cache", "DEL", base + date)
        check(get(1)[:2], (200, 44))
        k2 = physical()
        assert k2 != k
        check(R.cli("cache", "EXISTS", k), "1")
        out["ttl_before_after_hits_ms"] = [ttl1, ttl2]
        out["evicted_marker_new_key"] = True

        # First calculation has read the old schedule. PostgreSQL is blocking only its final slot SQL.
        keys = scan(base + date + ":2:*")
        if keys:
            R.cli("cache", "DEL", *keys)
        old_gen = R.cli("cache", "GET", base + "*")
        with concurrent.futures.ThreadPoolExecutor(max_workers=15) as users:
            with sql_session(lab, db, begin=True) as holder:
                pid = int(holder("SELECT pg_backend_pid();")[0])
                holder("LOCK TABLE store_table IN ACCESS EXCLUSIVE MODE;")
                old = users.submit(get, 0)
                until(
                    lambda: L.sql(
                        lab,
                        db,
                        f"SELECT count(*) FROM pg_stat_activity WHERE {pid}=ANY(pg_blocking_pids(pid)) AND query ILIKE '%SELECT count(st.id)%';",
                    ),
                    lambda x: x == "1",
                    5,
                )
                L.sql(
                    lab, db, "UPDATE store SET reservation_slot_minutes=60 WHERE id=1;"
                )
                until(
                    lambda: R.cli("cache", "GET", base + "*"),
                    lambda gen: bool(gen) and gen != old_gen,
                    3,
                )
                holder("COMMIT;")
            check(old.result(timeout=5)[:2], (200, 44))
            check(get(1)[:2], (200, 22))
            check(get(2)[:2], (200, 22))
            out["late_old_fill"] = {"old_slots": 44, "next_generation_slots": 22}

            # A rolled-back business change is invisible to the durable invalidation drain.
            token = R.cli("cache", "GET", base + "*")
            with sql_session(lab, db, begin=True) as tx:
                tx("UPDATE store SET reservation_slot_minutes=30 WHERE id=1;")
                check(R.cli("cache", "GET", base + "*"), token)
                tx("ROLLBACK;")
            check(get(2)[:2], (200, 22))
            check(R.cli("cache", "GET", base + "*"), token)
            out["rollback_kept_generation"] = True

            for mode in ("normal", "outage"):
                if mode == "outage":
                    R.power("cache", False)
                else:
                    keys = scan(base + date + ":2:*")
                    if keys:
                        R.cli("cache", "DEL", *keys)
                try:
                    with sql_session(lab, db, begin=True) as holder:
                        pid = int(holder("SELECT pg_backend_pid();")[0])
                        holder("LOCK TABLE store_table IN ACCESS EXCLUSIVE MODE;")
                        futures = [users.submit(get, i % 3) for i in range(12)]
                        count_sql = f"""SELECT count(*) FROM pg_stat_activity WHERE {pid}=ANY(pg_blocking_pids(pid))
                            AND query ILIKE '%SELECT count(st.id)%';"""
                        until(lambda: L.sql(lab, db, count_sql), lambda n: n == "3", 4)
                        time.sleep(0.12)
                        check(L.sql(lab, db, count_sql), "3")
                        if mode == "outage":
                            extra = get(0, 3)
                            check((extra[0], extra[2]), (503, "2"))
                        holder("COMMIT;")
                    assert all(f.result(timeout=5)[:2] == (200, 22) for f in futures)
                    out[mode] = {
                        "clients": 12,
                        "actual_db_calculations_blocked": 3,
                        "excess_other_key_http": 503 if mode == "outage" else None,
                    }
                finally:
                    if mode == "outage":
                        R.power("cache", True)
        L.save(folder / "cache-edges.json", out)
        print(out, flush=True)


if __name__ == "__main__":
    with L.lock():
        run()
