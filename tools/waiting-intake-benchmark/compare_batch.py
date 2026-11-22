"""Paired same-JAR tests of direct commits and short store-specific commits on loopback."""

import argparse
import csv
import json
import os
from collections import Counter, defaultdict

import measure as M

L = M.L
ARMS = {"direct": (False, 1, 0), "group1": (True, 1, 0), "batch32": (True, 32, 2)}
SHAPES = [("open", 3), ("w5k", 3), ("w10k", 3), ("w1k", 3), ("open", 1)]


def setting(arm):
    enabled, size, window = ARMS[arm]
    os.environ.update(
        BATCH_TRIAL_ENABLED=str(enabled).lower(), BATCH_TRIAL_SIZE=str(size), BATCH_TRIAL_WINDOW=str(window)
    )


def read(path):
    return json.loads(path.read_text())


def csv_rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream))


def summarize_trial(folder):
    cfg = read(folder / "config.json")
    r = M.summarize(folder)
    samples = [json.loads(line) for line in (folder / "client.jsonl").read_text().splitlines()]
    records = csv_rows(folder / "receipts.csv")
    keys = csv_rows(folder / "keys.csv")
    ac = {int(row["member_id"]): row["insert_xid"] for row in records}
    sizes = Counter(ac.values())
    tx_mismatch = sum(ac.get(int(k["member_id"])) != k["insert_xid"] for k in keys)
    origin = int((folder / "origin-us.txt").read_text())
    logs = M.access_logs(folder)
    times = defaultdict(list)
    for sample in samples:
        if (
            sample["Cohort"] != "hot"
            or sample["Outcome"] != "http_202"
            or len(logs.get(sample["ID"], [])) != 1
        ):
            continue
        start, end, status = logs[sample["ID"]][0]
        if status == 202:
            times["client_to_write"].append((sample["WrittenUS"] - sample["StartUS"]) / 1000)
            times["write_to_tomcat_begin"].append(start - (origin + sample["WrittenUS"]) / 1000)
            times["tomcat"].append(end - start)
            times["tomcat_end_to_client"].append((origin + sample["EndUS"]) / 1000 - end)
    before, after = [read(folder / ("batch-" + time + ".json")) for time in ("before", "after")]

    def metric(snap, stat):
        if not all(code == 200 for code, _ in snap):
            return None
        return sum(
            next(m["value"] for m in body["measurements"] if m["statistic"] == stat) for _, body in snap
        )

    cb, ca = metric(before, "COUNT"), metric(after, "COUNT")
    batch_calls = None if cb is None or ca is None else int(ca - cb)
    pool_before, pool_after = [read(folder / ("pool-" + time + ".json")) for time in ("before", "after")]
    pool_sec = None
    if all("TOTAL_TIME" in v.get("hikaricp.connections.acquire", {}) for v in pool_before + pool_after):
        pool_sec = round(
            sum(
                a["hikaricp.connections.acquire"]["TOTAL_TIME"]
                - b["hikaricp.connections.acquire"]["TOTAL_TIME"]
                for a, b in zip(pool_after, pool_before)
            ),
            4,
        )
    blocked = Counter()
    polls = [json.loads(l) for l in (folder / "observations.jsonl").read_text().splitlines()]
    for o in polls:
        for activity in o.get("db", {}).get("activity", []):
            if activity["blockers"]:
                query = activity["query"].lower()
                if "waiting_intake_day" in query:
                    blocked["day"] += 1
                elif "from member" in query:
                    blocked["member"] += 1
                else:
                    blocked["other"] += 1
    tx_sizes = M.distribution(list(sizes.values()))
    peak = tx_sizes.get("max", 0)
    arm = cfg["variant"]
    _, limit, window = ARMS[arm]
    valid_instrument = batch_calls == 0 if arm == "direct" else batch_calls == len(sizes)
    valid_instrument = valid_instrument and peak <= limit and not tx_mismatch
    # Sparse arrivals legitimately commit individually. XIDs and the independent meter validate the instrument.
    bg_recovery = r["bg_recovery"]["two_windows_end_s"]
    diag = {
        "arm": arm,
        "batch_limit": limit,
        "collection_ms": window,
        "receipt_transactions": len(sizes),
        "batch_size": tx_sizes,
        "batch_size_mean": round(len(records) / len(sizes), 3) if sizes else None,
        "meter_transactions": batch_calls,
        "first_key_xid_mismatches": tx_mismatch,
        "instrument_agrees": valid_instrument,
        "multi_request_commit_observed": peak > 1,
        "matched_202_timing_ms": {k: M.distribution(v) for k, v in times.items()},
        "blocked_session_samples": dict(blocked),
        "pool_acquire_s_total": pool_sec,
        "host_load1_max": max((o["host_load"][0] for o in polls), default=None),
        "candidate_pass": bool(
            valid_instrument and r["verdict"] == "pass" and bg_recovery is not None and bg_recovery <= 20
        ),
    }
    L.save(folder / "batch-result.json", diag)
    return cfg, r, diag


def write_csv(path, rows):
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=rows[0], lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def report():
    out = L.OUT.parent / "batch-summary"
    out.mkdir(exist_ok=True)
    runs, details, timings = [], [], []
    for folder in sorted(L.OUT.glob("*app")):
        if not (folder / "result.json").exists():
            continue
        cfg, r, b = summarize_trial(folder)
        prefix = {"run": folder.name, "arm": cfg["variant"], "case": cfg["case"], "apps": cfg["Apps"]}
        row = {
            **prefix,
            **{
                k: r[k]
                for k in (
                    "target",
                    "verdict",
                    "started_in_window",
                    "wire_in_window",
                    "server_in_window",
                    "server_anytime",
                    "ack202",
                    "db",
                    "db_missing",
                    "db_without_ack",
                    "acks_within_1s",
                    "acks_within_2s",
                    "duplicate_members",
                    "duplicate_receipt_ids",
                    "sequence_gaps",
                    "causal_order_violations",
                    "invalid_keys",
                    "bg_201",
                    "bg_db",
                    "bg_errors",
                    "pool_pending_peak",
                    "burst_202_per_second",
                )
            },
            "ack_mismatch": len(r["ack_db_mismatch"]),
            "other_mismatch": len(r["bg_mismatches"]),
            "ack_p50_ms": r["ack_ms"].get("p50"),
            "ack_p95_ms": r["ack_ms"].get("p95"),
            "ack_p99_ms": r["ack_ms"].get("p99"),
            "bg_recovery_s": r["bg_recovery"]["two_windows_end_s"],
            **{
                k: b[k]
                for k in (
                    "receipt_transactions",
                    "batch_size_mean",
                    "meter_transactions",
                    "first_key_xid_mismatches",
                    "instrument_agrees",
                    "candidate_pass",
                    "pool_acquire_s_total",
                    "host_load1_max",
                )
            },
            "max_batch_size": b["batch_size"].get("max"),
            "blocked_day_samples": b["blocked_session_samples"].get("day", 0),
        }
        for op, expected in (("availability", 200), ("waiting", 201)):
            bg = r["by_start_phase_ms"].get(f"other/{op}/http_{expected}/spike", {})
            row["bg_" + op + "_spike_success_n"] = bg.get("n", 0)
            row["bg_" + op + "_spike_p95_ms"] = bg.get("p95")
        runs.append(row)
        for name, vals in b["matched_202_timing_ms"].items():
            timings.append({**prefix, "metric": name, **vals})
        for key in sorted(set(r["by_start_phase_ms"]) | set(r["completed_by_phase"])):
            cohort, op, outcome, phase = key.split("/")
            vals = r["by_start_phase_ms"].get(key, {})
            count = r["completed_by_phase"].get(key, 0)
            details.append(
                {
                    **prefix,
                    "cohort": cohort,
                    "operation": op,
                    "outcome": outcome,
                    "phase": phase,
                    "n": vals.get("n", 0),
                    "p50_ms": vals.get("p50"),
                    "p95_ms": vals.get("p95"),
                    "p99_ms": vals.get("p99"),
                    "completed": count,
                    "completed_per_s": round(count / r["seconds_by_phase"][phase], 3),
                }
            )
    for name, rows in (("runs", runs), ("http", details), ("timing", timings)):
        if rows:
            write_csv(out / (name + ".csv"), rows)
    conditions = []
    for case, apps in SHAPES:
        group = [r for r in runs if r["arm"] == "batch32" and r["case"] == case and r["apps"] == apps]
        conditions.append(
            {
                "case": case,
                "apps": apps,
                "trials": len(group),
                "input_insufficient": sum(r["verdict"] == "insufficient" for r in group),
                "candidate_pass": sum(r["candidate_pass"] for r in group),
            }
        )
    gate = all(c["trials"] == 3 and c["candidate_pass"] == 3 for c in conditions)
    L.save(
        out / "gate.json",
        {
            "eligible": gate,
            "conditions": conditions,
            "scope": "local admission criteria, including B recovery",
        },
    )
    print(len(runs), "complete trials; batch32 gate", gate, flush=True)
    return gate


def schedule():
    for case, apps in SHAPES:
        arms = ["direct", "batch32", "group1"] if (case, apps) == ("open", 3) else ["direct", "batch32"]
        for repetition in range(3):
            i = repetition % len(arms)
            for arm in arms[i:] + arms[:i]:
                yield case, apps, arm


def suite(resume=False):
    complete = sorted(p.parent for p in L.OUT.glob("*app/batch-result.json"))
    plan = list(schedule())
    if complete and not resume:
        raise RuntimeError("Completed trial data exists; use resume or a new lab")
    if len(complete) > len(plan):
        raise RuntimeError("Unexpected extra trials")
    for index, (case, apps, arm) in enumerate(plan):
        if index < len(complete):
            cfg, _, diag = summarize_trial(complete[index])
            if (cfg["case"], cfg["Apps"], cfg["variant"]) != (case, apps, arm) or not diag[
                "instrument_agrees"
            ]:
                raise RuntimeError("Trial prefix or XID/meter reconciliation failed: " + complete[index].name)
            continue
        setting(arm)
        folder = M.trial(case, apps, arm)
        _, r, diag = summarize_trial(folder)
        print(
            "RESULT",
            arm,
            case,
            apps,
            r["wire_in_window"],
            r["ack202"],
            r["db"],
            r["ack_ms"],
            diag["receipt_transactions"],
            diag["batch_size_mean"],
            r["bg_errors"],
            flush=True,
        )
        if not diag["instrument_agrees"] or any(read(p)["oom"] for p in folder.glob("container-*.json")):
            raise RuntimeError("Instrument or process limits failed; inspect this preserved trial")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("smoke", "suite", "resume", "report", "gate", "retry"))
    args = parser.parse_args()
    if args.mode in ("report", "gate"):
        ok = report()
        raise SystemExit(2 if args.mode == "gate" and not ok else 0)
    with L.lock():
        if args.mode == "smoke":
            setting("batch32")
            M.trial("smoke", 3, "batch32")
        elif args.mode == "retry":
            setting("batch32")
            M.replay_committed_timeouts("batch32")
        else:
            suite(resume=args.mode == "resume")
