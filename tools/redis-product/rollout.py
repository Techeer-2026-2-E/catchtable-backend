"""Local-only stage transition; use the product report gate and the owned DB identity."""

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import redis_lab as T

L = T.L


def enable(lab, db, store, day, capacity, gate):
    if not re.fullmatch(r"rb0_(?:full|product|roll)[0-9]+", db):
        raise ValueError("Expected an owned trial clone")
    date = dt.date.fromisoformat(day)
    if (
        not isinstance(store, int)
        or not isinstance(capacity, int)
        or store < 1
        or capacity < 1
    ):
        raise ValueError("Invalid scope")
    expected = {("w1k", 3), ("w5k", 3), ("w10k", 3), ("open", 3), ("open", 1)}
    cases = gate.get("cases", [])
    if (
        gate.get("eligible") is not True
        or gate.get("jars") != [L.digest(L.JAR)]
        or gate.get("scope") != "local product comparison"
        or gate.get("expected_trials") not in (15, 45)
        or gate.get("observed_trials") != gate["expected_trials"]
        or not isinstance(cases, list)
        or not all(isinstance(c, dict) for c in cases)
        or len(cases) != len(expected)
        or {(c.get("case"), c.get("apps")) for c in cases} != expected
        or not all(
            c.get("trials") == c.get("passing") == 3 and c.get("insufficient") == 0
            for c in cases
        )
    ):
        return "BLOCKED"
    return L.sql(
        lab, db, f"SELECT enable_waiting_intake({store}, DATE '{date}', {capacity});"
    )


def scenario():
    import time

    lab = L.state()
    L.validate_fixture_date(lab)
    db = "rb0_roll" + str(time.time_ns())
    folder = L.OUT / (db + "-rollout")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE};")
    with L.apps(lab, db, 1, folder):
        today = L.sql(lab, db, "SELECT current_date;")
        tomorrow = L.sql(lab, db, "SELECT current_date+1;")
        passed = {
            "eligible": True,
            "scope": "local product comparison",
            "jars": [L.digest(L.JAR)],
            "observed_trials": 15,
            "expected_trials": 15,
            "cases": [
                {"case": k, "apps": n, "trials": 3, "passing": 3, "insufficient": 0}
                for k, n in (
                    ("open", 3),
                    ("w1k", 3),
                    ("w5k", 3),
                    ("w10k", 3),
                    ("open", 1),
                )
            ],
        }
        failed = {**passed, "eligible": False}
        assert (
            enable(lab, db, 2, tomorrow, 4, {**passed, "jars": ["wrong"]}) == "BLOCKED"
        )
        results = [
            enable(lab, db, 1, today, 4, passed),
            enable(lab, db, 2, tomorrow, 4, failed),
        ]
        assert (
            L.sql(lab, db, "SELECT count(*) FROM waiting_intake_day WHERE store_id=2;")
            == "0"
        )
        results.extend(
            [
                enable(lab, db, 2, tomorrow, 4, passed),
                enable(lab, db, 2, tomorrow, 4, passed),
            ]
        )
        assert results == ["ENABLED", "BLOCKED", "ENABLED", "ALREADY_ENABLED"]
        assert L.sql(lab, db, "SELECT retire_waiting_intake(2,current_date+1);") == "t"
        L.save(
            folder / "rollout.json",
            {
                "scope": "functional gate branches on synthetic days",
                "db": db,
                "outcomes": results,
                "later_day_retired": True,
                "actual_performance_gate_used": False,
            },
        )
        print(folder.name, results, flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=("scenario", "expand", "retire"))
    p.add_argument("--gate", type=Path)
    p.add_argument("--db")
    p.add_argument("--store", type=int)
    p.add_argument("--day")
    p.add_argument("--capacity", type=int)
    a = p.parse_args()
    with L.lock():
        if a.mode == "scenario":
            scenario()
        elif a.mode == "expand":
            g = json.loads(a.gate.read_text())
            result = enable(L.state(), a.db, a.store, a.day, a.capacity, g)
            print(result)
            raise SystemExit(2 if result == "BLOCKED" else 0)
        else:
            if (
                not re.fullmatch(r"rb0_(?:full|product|roll)[0-9]+", a.db)
                or a.store < 1
            ):
                raise ValueError("Invalid scope")
            d = dt.date.fromisoformat(a.day)
            result = L.sql(
                L.state(), a.db, f"SELECT retire_waiting_intake({a.store},DATE '{d}');"
            )
            print("RETIRED" if result == "t" else "DRAIN_REQUIRED")
            raise SystemExit(0 if result == "t" else 2)
