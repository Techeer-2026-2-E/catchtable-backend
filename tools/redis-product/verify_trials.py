"""Replay the original HTTP observations against the PostgreSQL rows without starting a server."""

import argparse
from collections import Counter
import csv
import datetime as dt
import gzip
import json
import re
from pathlib import Path

from harness.observations import distribution
from suite import core_plan


def table(path):
    with path.open() as source:
        return list(csv.DictReader(source))


def verify(folder, expected_jar):
    cfg = json.loads((folder / "config.json").read_text())
    result = json.loads((folder / "result.json").read_text())
    if cfg["jar"] != expected_jar or cfg["Flow"] != "waiting" or not cfg["Follow"]:
        raise ValueError("incompatible input in " + folder.name)
    receipts = table(folder / "ledger.csv")
    actual = [r for r in table(folder / "waitings.csv") if r["store_id"] == "1"]
    ledger = {int(r["member_id"]): r for r in receipts}
    by_member = {int(r["member_id"]): r for r in actual}
    assert len(ledger) == len(receipts) and len(by_member) == len(actual)
    assert len({r["receipt_id"] for r in receipts}) == len(receipts)
    assert [int(r["intake_sequence"]) for r in receipts] == list(
        range(1, len(receipts) + 1)
    )
    numbers = [int(r["wait_number"]) for r in receipts if r["wait_number"]]
    assert numbers == sorted(set(numbers))
    for member, r in ledger.items():
        assert r["request_key"] == "intake-" + str(member)
        if r["waiting_id"]:
            row = by_member[member]
            assert (
                r["waiting_id"] == row["id"] and r["wait_number"] == row["wait_number"]
            )
            assert r["received_date"] == r["waiting_date"] == cfg["day"]
    assert {v["id"] for v in actual} == {
        v["waiting_id"] for v in receipts if v["waiting_id"]
    }
    first = {}
    final = {}
    started = set()
    wire = set()
    attempted = set()
    begin = cfg["Before"] * 1_000_000
    end = begin + cfg["Window"] * 1_000_000
    with gzip.open(folder / "client.jsonl.gz", "rt") as stream:
        for line in stream:
            q = json.loads(line)
            if q["Cohort"] != "hot":
                continue
            member = q["Member"]
            body = q["Reply"]
            if q["Op"] == "waiting":
                assert member not in attempted
                attempted.add(member)
                if begin <= q["StartUS"] < end:
                    started.add(member)
                if begin <= q["WrittenUS"] < end:
                    wire.add(member)
                if q["Status"] in (200, 201, 202) and body.get("receiptId"):
                    first[member] = (q["EndUS"] - q["StartUS"]) / 1000
            if body.get("receiptId"):
                assert (
                    ledger[member]["receipt_id"] == body["receiptId"]
                    and int(ledger[member]["intake_sequence"]) == body["intakeSequence"]
                )
            if body.get("waitingId") and q["Status"] in (200, 201):
                assert (
                    int(by_member[member]["id"]) == body["waitingId"]
                    and int(by_member[member]["wait_number"]) == body["waitNumber"]
                )
                elapsed = (q["EndUS"] - q["JourneyStartUS"]) / 1000
                if elapsed <= cfg["FinalLimit"] * 1000:
                    final.setdefault(member, elapsed)
    assert attempted == set(range(1000, 1000 + cfg["Users"]))
    counts = (
        len(started),
        len(wire),
        len(first),
        len(final),
        len(receipts),
        len(actual),
    )
    fields = (
        "started",
        "wire",
        "first_success",
        "final_observed",
        "db_receipts",
        "db_waiting",
    )
    assert counts == tuple(result[k] for k in fields), folder.name
    assert distribution(list(first.values())) == result["first_ms"]
    assert distribution(list(final.values())) == result["final_ms"]
    opening = None
    if cfg.get("OpeningEpochUS"):
        dates = []
        for row in receipts:
            stamp = re.sub(r"([+-]\d{2})$", r"\1:00", row["accepted_at"])
            stamp = re.sub(r"\.(\d+)", lambda m: "." + m.group(1).ljust(6, "0"), stamp)
            dates.append(dt.datetime.fromisoformat(stamp).timestamp())
        opening = (min(dates) * 1e6 - cfg["OpeningEpochUS"]) / 1000
        assert opening >= 0, folder.name
    limits = json.loads((folder / "app-limits.json").read_text())
    assert sum(x["cpu"] for x in limits) == 3_000_000_000
    assert (
        sum(
            int(next(a[4:-1] for a in x["args"] if a.startswith("-Xmx")))
            for x in limits
        )
        == 768
    )
    before = json.loads((folder / "before.json").read_text())
    assert sum(x["hikaricp.connections.max"]["VALUE"] for x in before) == 12
    return {
        "trial": folder.name,
        "case": cfg["case"],
        "apps": cfg["Apps"],
        "jar": cfg["jar"],
        "client": cfg["client"],
        "opening_to_first_recorded_acceptance_ms": opening,
        **{
            k: result[k]
            for k in (
                *fields,
                "target",
                "first_ms",
                "final_ms",
                "fifo_inversions",
                "B_errors",
                "B_recovery_s",
                "verdict",
                "apps_with_heap_errors",
            )
        },
    }


def review(root, tag, jar):
    if not __debug__:
        raise RuntimeError("Run without Python -O so every evidence check executes")
    rows = []
    for cfg in sorted(root.glob("*/config.json")):
        metadata = json.loads(cfg.read_text())
        if metadata.get("campaign") != tag:
            continue
        if not (cfg.parent / "result.json").is_file():
            raise ValueError("unfinished trial: " + cfg.parent.name)
        rows.append(verify(cfg.parent, jar))
    expected = Counter((k, n) for k, n, _, _ in core_plan())
    if (
        Counter((r["case"], r["apps"]) for r in rows) != expected
        or len({r["client"] for r in rows}) != 1
    ):
        raise ValueError("incomplete repetitions or mixed generator binaries")
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = review(args.root, args.tag, args.jar)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(len(result), "trials reconciled")
