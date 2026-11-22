"""Boundary checks on disposable, owned containers. Stop one JVM before the write or at COMMIT."""

import concurrent.futures
import json
import os
import threading
import time
from http import client

import measure as M
from preflight import sql_session

L = M.L


def sender(port, start):
    def send(member):
        start.wait(timeout=4)
        try:
            return M.http(port, "POST", M.API, member, {"storeId": 1, "partyCount": 2}, f"fault-{member}")
        except (OSError, client.HTTPException) as e:
            return (0, {"transport": type(e).__name__}, None, None)

    return send


def snapshot(lab, db):
    text = L.sql(
        lab,
        db,
        """SELECT coalesce(json_agg(to_jsonb(r) ORDER BY intake_sequence),'[]')
        FROM (SELECT member_id,receipt_id,intake_sequence,xmin::text AS xid FROM waiting_receipt WHERE store_id=1) r;""",
    )
    return {
        "rows": json.loads(text),
        "day": L.sql(lab, db, "SELECT last_sequence,pending_count FROM waiting_intake_day WHERE store_id=1;"),
        "keys": L.sql(lab, db, "SELECT count(*) FROM waiting_receipt_key WHERE member_id IN (701,702);"),
    }


def run(phase):
    lab = L.state()
    L.JAR = L.OUT / "measured.jar"
    if L.digest(L.JAR) != L.digest(M.ROOT / "build/libs/catchtable-0.0.1-SNAPSHOT.jar"):
        raise RuntimeError("Unexpected JAR")
    stamp = str(time.time_ns())
    db = "rb0_fault" + stamp
    folder = L.OUT / f"{stamp}-fault-{phase}"
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE};")
    with L.apps(lab, db, 3, folder) as (ports, ids):
        L.sql(
            lab,
            db,
            "INSERT INTO waiting_intake_day(store_id,received_date,capacity) VALUES(1,current_date,2);",
        )
        if phase == "commit":
            L.sql(
                lab,
                db,
                """CREATE TABLE batch_fault_gate (id int PRIMARY KEY);
                INSERT INTO batch_fault_gate VALUES (1);
                CREATE FUNCTION batch_fault_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM id FROM batch_fault_gate WHERE id=1 FOR UPDATE; RETURN NEW; END; $$;
                CREATE CONSTRAINT TRIGGER batch_fault_commit AFTER INSERT ON waiting_receipt_key
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION batch_fault_commit();""",
            )
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as clients:
            with sql_session(lab, db, begin=True) as gate:
                holder = int(gate("SELECT pg_backend_pid();")[0])
                gate(
                    "SELECT id FROM batch_fault_gate FOR UPDATE;"
                    if phase == "commit"
                    else "SELECT store_id FROM waiting_intake_day WHERE store_id=1 FOR UPDATE;"
                )
                send = sender(ports[0], threading.Barrier(2))
                first = [clients.submit(send, m) for m in (701, 702)]
                for _ in range(60):
                    blocked = L.sql(
                        lab,
                        db,
                        f"""SELECT query FROM pg_stat_activity WHERE datname=current_database()
                        AND application_name='rb0-app-0' AND {holder}=ANY(pg_blocking_pids(pid));""",
                    )
                    if blocked:
                        break
                    time.sleep(0.05)
                assert blocked and all(not f.done() for f in first), blocked
                if phase == "commit":
                    assert "COMMIT" in blocked.upper(), blocked
                else:
                    assert "waiting_intake_day" in blocked, blocked
                before = snapshot(lab, db)
                assert before == {"rows": [], "day": "0|0", "keys": "0"}
                b_status = M.http(
                    ports[0], "POST", M.API, 15990, {"storeId": 2, "partyCount": 2}, "other-store"
                )[0]
                assert b_status == 201 and all(not f.done() for f in first)
                # Only the exact first app returned by this L.apps invocation, in this new named DB, may be killed.
                identity = L.docker(
                    "inspect",
                    "--format",
                    '{{.Id}}|{{index .Config.Labels "io.catchtable.intake-batch"}}|{{.Name}}',
                    ids[0],
                )
                assert identity == f"{ids[0]}|{lab['tag']}|/{lab['tag']}-{db}-0", identity
                L.docker("kill", "--signal", "KILL", ids[0])
                original = [f.result(timeout=6) for f in first]
                assert all(x[0] == 0 for x in original), original
                gate("ROLLBACK;")
            for _ in range(60):
                if (
                    L.sql(
                        lab,
                        db,
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name='rb0-app-0';",
                    )
                    == "0"
                ):
                    break
                time.sleep(0.05)
            else:
                raise RuntimeError("Killed app's PG connection still active")
            after = snapshot(lab, db)
            committed = len(after["rows"])
            assert committed in ((0, 2) if phase == "commit" else (0,))
            assert after["day"] == f"{committed}|{committed}" and after["keys"] == str(committed)
            if committed:
                assert len({r["xid"] for r in after["rows"]}) == 1
            again = sender(ports[1], threading.Barrier(2))
            futures = [clients.submit(again, m) for m in (701, 702)]
            retries = [f.result(timeout=8) for f in futures]
        final = snapshot(lab, db)
        assert final["day"] == "2|2" and final["keys"] == "2" and len(final["rows"]) == 2
        assert [r["intake_sequence"] for r in final["rows"]] == [1, 2]
        assert len({r["xid"] for r in final["rows"]}) == 1
        by_member = {r["member_id"]: r for r in final["rows"]}
        for member, (code, body, location, _) in zip((701, 702), retries):
            assert (
                code == (200 if committed else 202) and body["receiptId"] == by_member[member]["receipt_id"]
            )
            assert body["intakeSequence"] == by_member[member]["intake_sequence"]
            assert M.http(ports[2], "GET", location, member)[:2] == (200, body)
        if committed:
            assert after == final
    L.save(
        folder / "fault-result.json",
        {
            "phase": phase,
            "db": db,
            "blocked_sql": blocked,
            "before": before,
            "first_http": original,
            "other_store_status_while_blocked": b_status,
            "after_kill_before_retry": after,
            "retry": retries,
            "final": final,
            "same_initial_decision_for_pair": True,
        },
    )
    print(
        folder.name,
        committed,
        "committed before retry;",
        [r[0] for r in retries],
        "retry;",
        final["day"],
        flush=True,
    )


if __name__ == "__main__":
    os.environ.update(BATCH_TRIAL_ENABLED="true", BATCH_TRIAL_SIZE="2", BATCH_TRIAL_WINDOW="250")
    with L.lock():
        run("before-write")
        run("commit")
