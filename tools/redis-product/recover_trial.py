"""Recover receipts in an isolated clone of a failed run, including receipts from yesterday."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
import io
import json
import os
from pathlib import Path
import re
import time

import redis_lab as R
from check import check, http, until

L = R.L


def recover(source: Path):
    lab = L.state()
    cfg = json.loads((source / "config.json").read_text())
    if (
        not re.fullmatch(r"rb0_full[0-9]+", cfg["db"])
        or cfg["db"] != "rb0_full" + source.name.split("-")[0]
    ):
        raise ValueError("Expected a recorded isolated trial")
    L.inspect_db(lab)
    db = "rb0_product" + str(time.time_ns())
    folder = L.OUT / (db + "-recovered")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {cfg['db']}")
    query = """COPY (SELECT r.member_id,r.receipt_id,r.intake_sequence,r.status,k.request_key,w.id AS waiting_id,w.wait_number
              FROM waiting_receipt r JOIN waiting_receipt_key k USING(member_id,receipt_id)
              LEFT JOIN waiting w ON w.id=r.waiting_id WHERE r.store_id=1 ORDER BY r.intake_sequence) TO STDOUT WITH CSV HEADER"""

    def snapshot(name):
        data = L.sql(lab, db, query)
        (folder / name).write_text(data + "\n")
        return list(csv.DictReader(io.StringIO(data)))

    before = snapshot("before.csv")
    assert before and len({x["receipt_id"] for x in before}) == len(before)
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(R.settings(True, True, True, True))
    with L.apps(lab, db, 3, folder) as (ports, _):
        until(
            lambda: L.sql(
                lab,
                db,
                "SELECT sum(pending_count) FROM waiting_intake_day WHERE store_id=1",
            ),
            lambda n: n == "0",
            150,
        )
        after = snapshot("after.csv")
        stable = lambda row: (
            row["member_id"],
            row["receipt_id"],
            row["intake_sequence"],
            row["request_key"],
        )
        check(list(map(stable, before)), list(map(stable, after)))
        numbers = [int(row["wait_number"]) for row in after]
        check(numbers, sorted(set(numbers)))

        def replay(row):
            member = int(row["member_id"])
            code, body, *_ = http(
                ports[member % 3],
                "POST",
                "/api/user/waitings",
                member,
                {"storeId": 1, "partyCount": 2},
                row["request_key"],
            )
            check(
                (
                    code,
                    body.get("receiptId"),
                    body.get("intakeSequence"),
                    body.get("waitingId"),
                    body.get("waitNumber"),
                ),
                (
                    200,
                    row["receipt_id"],
                    int(row["intake_sequence"]),
                    int(row["waiting_id"]),
                    int(row["wait_number"]),
                ),
            )

        with ThreadPoolExecutor(max_workers=8) as callers:
            list(callers.map(replay, after))
        check(snapshot("after-replay.csv"), after)
    result = {
        "source": source.name,
        "source_jar": cfg["jar"],
        "recovery_jar": L.digest(L.JAR),
        "accepted": len(before),
        "unfinished_before": sum(not r["waiting_id"] for r in before),
        "same_key_and_number_after_restart": len(after),
        "inversions": 0,
        "duplicates": 0,
    }
    L.save(folder / "recovery.json", result)
    print(result, flush=True)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trial", type=Path)
    args = parser.parse_args()
    with L.lock():
        recover(args.trial)
