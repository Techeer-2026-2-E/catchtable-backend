"""Run independent product trials sequentially and resume from verified per-trial evidence."""

import argparse
from collections import Counter
import json
import os
from campaign import test, L, csv_save


def plan():
    for rep in range(3):
        arms = [
            ("admit-sync", "host"),
            ("admit-async", "host"),
            ("admit-async", "published-from-linux"),
            ("admit-async", "bridge"),
        ]
        for arm, network in arms[rep:] + arms[:rep]:
            yield "open", 3, arm, network
    for case in ("read", "hold"):
        for rep in range(3):
            for arm in ("legacy", "cache") if rep % 2 == 0 else ("cache", "legacy"):
                yield case, 3, arm, "host"
    for rep in range(3):
        arms = ["legacy", "waiting", "both"]
        for arm in arms[rep:] + arms[:rep]:
            yield "open", 3, arm, "host"
    for case, apps in (("w1k", 3), ("w5k", 3), ("w10k", 3), ("open", 1)):
        for _ in range(3):
            yield case, apps, "both", "host"


def core_plan(network="host"):
    for _ in range(3):
        for case, apps in (
            ("open", 3),
            ("w1k", 3),
            ("w5k", 3),
            ("w10k", 3),
            ("open", 1),
        ):
            yield case, apps, "both", network


def complete(tag="product-v1"):
    return [
        f
        for f in sorted(L.OUT.glob("*-?*app/result.json"))
        if json.loads((f.parent / "config.json").read_text()).get("campaign") == tag
    ]


def choose(tag):
    subset = [json.loads(f.read_text()) for f in complete(tag)]
    stats = {}
    for arm in ("admit-sync", "admit-async"):
        rs = [
            x
            for x in subset
            if x["arm"] == arm
            and x["network"] == "host"
            and x["case"] == "open"
            and x["apps"] == 3
        ]
        if len(rs) != 3:
            return True
        # Prefer complete measured inputs and delivered receipts before comparing successful latency.
        stats[arm] = [
            sum(x["wire"] == x["target"] for x in rs),
            sum(x["first_success"] for x in rs),
            -sum(x["B_errors"] for x in rs),
            -max(x["first_ms"].get("p95", 1e9) for x in rs),
        ]
    pick = stats["admit-async"] > stats["admit-sync"]
    L.save(
        L.OUT.parent / "async-selection.json",
        {"observed": stats, "use_async": pick, "scope": "same-JAR product load trial"},
    )
    return pick


def incident(folder, result):
    containers = list(folder.glob("container-*.json"))
    if len(containers) != result["apps"]:
        return True
    return (
        not result["integrity"]
        or bool(result.get("apps_with_heap_errors"))
        or any(json.loads(p.read_text())["oom"] for p in containers)
        or any(
            "OutOfMemoryError" in p.read_text(errors="replace")
            for p in folder.glob("app-*.log")
        )
    )


def suite(recipe, tag, core):
    prefix = complete(tag)
    artifact = L.digest(L.JAR)
    if len(prefix) > len(recipe):
        raise ValueError("Unexpected completed trial count")
    for i, (case, apps, arm, network) in enumerate(recipe):
        if i < len(prefix):
            c = json.loads((prefix[i].parent / "config.json").read_text())
            if incident(prefix[i].parent, json.loads(prefix[i].read_text())):
                raise RuntimeError(
                    "Preserved trial contains an incident: " + prefix[i].parent.name
                )
            if c["jar"] != artifact:
                raise ValueError("Cannot resume using a different product JAR")
            client = L.OUT / (
                "product-client" if network == "host" else "product-client-linux"
            )
            if c["client"] != L.digest(client) or c.get("app_tuning", {}) != json.loads(
                os.environ.get("CT_PRODUCT_APP_TUNING", "{}")
            ):
                raise ValueError(
                    "Cannot resume with a different generator or resource configuration"
                )
            if (c["case"], c["Apps"], c["arm"], c["Network"]) != (
                case,
                apps,
                arm,
                network,
            ):
                raise ValueError("Trial prefix changed")
            continue
        os.environ["CT_PRODUCT_CAMPAIGN"] = tag
        if not core and arm in ("waiting", "both"):
            os.environ["CT_PRODUCT_ASYNC_CHOSEN"] = "true" if choose(tag) else "false"
        folder = test(case, apps, arm, network)
        r = json.loads((folder / "result.json").read_text())
        if incident(folder, r):
            raise RuntimeError("Integrity or OOM incident in " + folder.name)
        L.save(
            L.OUT.parent / "progress.json",
            {
                "completed": i + 1,
                "planned": len(recipe),
                "latest": folder.name,
                "verdict": r["verdict"],
            },
        )


def report(recipe, tag, core):
    d = L.OUT.parent / ("core-summary" if core else "product-summary")
    d.mkdir(exist_ok=True)
    L.save(
        d / "gate.json", {"eligible": False, "scope": "report in progress", "jars": []}
    )
    rows = []
    details = []
    timing = []
    for f in complete(tag):
        r = json.loads(f.read_text())
        c = json.loads((f.parent / "config.json").read_text())
        prefix = {
            "trial": f.parent.name,
            "jar": c["jar"],
            "case": r["case"],
            "arm": r["arm"],
            "apps": r["apps"],
            "network": r["network"],
        }
        observations = [
            json.loads(l)
            for l in (f.parent / "observations.jsonl").read_text().splitlines()
        ]
        held = [o["db"]["registration_slots_held"] for o in observations if "db" in o]
        pending = [
            sum(p["hikaricp.connections.pending"]["VALUE"] for p in o["apps"])
            for o in observations
            if len(o.get("apps", [])) == r["apps"]
            and all(
                "VALUE" in p.get("hikaricp.connections.pending", {}) for p in o["apps"]
            )
        ]
        rows.append(
            {
                **prefix,
                **{
                    k: r[k]
                    for k in (
                        "target",
                        "started",
                        "wire",
                        "first_success",
                        "db_receipts",
                        "db_waiting",
                        "final_observed",
                        "fifo_inversions",
                        "integrity",
                        "B_errors",
                        "B_recovery_s",
                        "poll_count",
                        "verdict",
                    )
                },
                "incident": incident(f.parent, r),
                "client_sha256": c["client"],
                "fixture_sha256": c.get("fixture_source_sha256", "legacy-unrecorded"),
                "app_tuning": json.dumps(c.get("app_tuning", {}), sort_keys=True),
                "async": c["async"],
                "first_n": r["first_ms"]["n"],
                "first_p50_ms": r["first_ms"].get("p50"),
                "first_p95_ms": r["first_ms"].get("p95"),
                "first_p99_ms": r["first_ms"].get("p99"),
                "number_n": r["final_ms"]["n"],
                "number_p50_ms": r["final_ms"].get("p50"),
                "number_p95_ms": r["final_ms"].get("p95"),
                "number_p99_ms": r["final_ms"].get("p99"),
                "slot_sql": r["db"]["slot_count"]["calls"],
                "cache_hits": r["db"]["availability.cache.hit"],
                "pool_pending_peak": max(pending, default=None),
                "permit_peak_sampled": max(held, default=None),
            }
        )
        for k, v in r["timing_ms"].items():
            timing.append({**prefix, "interval": k, **v})
        for k in sorted(set(r["phase_ms"]) | set(r["phase_finished"])):
            cohort, op, outcome, phase = k.split("/")
            g = r["phase_ms"].get(k, {})
            details.append(
                {
                    **prefix,
                    "cohort": cohort,
                    "op": op,
                    "outcome": outcome,
                    "phase": phase,
                    "n": g.get("n", 0),
                    "p50_ms": g.get("p50"),
                    "p95_ms": g.get("p95"),
                    "p99_ms": g.get("p99"),
                    "finished": r["phase_finished"].get(k, 0),
                    "finished_per_s": round(
                        r["phase_finished"].get(k, 0) / r["phase_s"][phase], 3
                    ),
                }
            )
    d = L.OUT.parent / ("core-summary" if core else "product-summary")
    d.mkdir(exist_ok=True)
    csv_save(d / "runs.csv", rows)
    csv_save(d / "http.csv", details)
    csv_save(d / "timing.csv", timing)
    conditions = []
    for case, n in [("w1k", 3), ("w5k", 3), ("w10k", 3), ("open", 3), ("open", 1)]:
        rr = [r for r in rows if (r["case"], r["apps"], r["arm"]) == (case, n, "both")]
        conditions.append(
            {
                "case": case,
                "apps": n,
                "trials": len(rr),
                "passing": sum(r["verdict"] == "pass" for r in rr),
                "insufficient": sum(r["verdict"] == "insufficient" for r in rr),
            }
        )
    inputs = Counter((r["case"], r["apps"], r["arm"], r["network"]) for r in rows)
    artifacts = {r["jar"] for r in rows}
    ok = (
        inputs == Counter(recipe)
        and len(artifacts) == 1
        and len({r["fixture_sha256"] for r in rows}) == 1
        and len({r["app_tuning"] for r in rows}) == 1
        and all(
            len({r["client_sha256"] for r in rows if r["network"] == n}) == 1
            for n in {r["network"] for r in rows}
        )
        and all(r["integrity"] and not r["incident"] for r in rows)
        and all(x["trials"] == 3 and x["passing"] == 3 for x in conditions)
    )
    ok = ok and all(
        r["verdict"] == "pass" for r in rows if r["arm"] in ("cache", "waiting")
    )
    L.save(
        d / "gate.json",
        {
            "eligible": ok,
            "scope": "local product comparison",
            "jars": sorted(artifacts),
            "cases": conditions,
            "observed_trials": len(rows),
            "expected_trials": len(recipe),
        },
    )
    print(len(rows), "completed trials; expansion", ok, flush=True)
    return ok


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=("run", "report", "gate"))
    p.add_argument(
        "--core",
        action="store_true",
        help="only the 15 agreed waiting load conditions with repeats",
    )
    p.add_argument("--tag", help="evidence group for this build and fixture")
    p.add_argument("--network", choices=("host", "guest"), default="host")
    a = p.parse_args()
    tag = a.tag or ("product-core" if a.core else "product-v1")
    recipe = list(core_plan(a.network) if a.core else plan())
    if a.mode == "run":
        with L.lock():
            suite(recipe, tag, a.core)
    else:
        raise SystemExit(0 if report(recipe, tag, a.core) or a.mode == "report" else 2)
