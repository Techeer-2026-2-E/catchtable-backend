"""Regression checks for partial lab failures, timed-out clients, and fail-closed gates."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import redis_lab
import rollout
import suite
from harness import lab
from harness.observations import stop_process


class EvidenceTest(unittest.TestCase):
    def test_incomplete_initialization_preserves_only_the_owned_stopped_resources(self):
        with (
            tempfile.TemporaryDirectory() as path,
            patch.object(lab, "OUT", Path(path)),
        ):
            tag = "ct-product-1234abcd"
            lab.save(lab.OUT / "initializing.json", {"tag": tag})
            (lab.OUT / ".db-password").write_text("fixture-only")
            calls = []

            def docker(*args):
                calls.append(args)
                return {"ps": "owned-id", "inspect": tag, "stop": "owned-id"}[args[0]]

            with patch.object(lab, "docker", side_effect=docker):
                lab.archive_partial_init()
            saved = next(lab.OUT.glob("failed-init-*/preserved.json"))
            self.assertEqual(
                json.loads(saved.read_text())["stopped_owned_containers"], ["owned-id"]
            )
            self.assertFalse((lab.OUT / ".db-password").exists())
            self.assertIn(("stop", "--time", "2", "owned-id"), calls)

    def test_restore_attempted_for_each_node_when_second_stop_fails(self):
        calls = []

        def power(n, on):
            calls.append((n, on))
            if n == "master" and not on:
                raise RuntimeError("fixture failure")

        runtime = {"owner": "owned", "nodes": {"replica": "r", "master": "m"}}
        with (
            patch.object(redis_lab, "runtime", return_value=runtime),
            patch.object(redis_lab, "verify"),
            patch.object(redis_lab, "docker", return_value="true"),
            patch.object(redis_lab, "power", side_effect=power),
        ):
            with (
                self.assertRaises(RuntimeError),
                redis_lab.offline("replica", "master"),
            ):
                self.fail("partial shutdown is never a valid test phase")
        self.assertEqual(
            calls,
            [
                ("replica", False),
                ("master", False),
                ("master", True),
                ("replica", True),
            ],
        )

    def test_incidents_including_missing_observations_block_resume(self):
        with tempfile.TemporaryDirectory() as path:
            f = Path(path)
            r = {"apps": 1, "integrity": True}
            self.assertTrue(suite.incident(f, r))
            lab.save(f / "container-0.json", {"oom": False})
            self.assertFalse(suite.incident(f, r))
            (f / "app-0.log").write_text("java.lang.OutOfMemoryError")
            self.assertTrue(suite.incident(f, r))

    def test_mismatched_or_short_gate_never_calls_sql(self):
        with (
            tempfile.TemporaryDirectory() as path,
            patch.object(lab, "JAR", Path(path) / "test.jar"),
            patch.object(lab, "sql") as sql,
        ):
            lab.JAR.write_bytes(b"local test build")
            for g in (
                {"eligible": True},
                {
                    "eligible": True,
                    "jars": ["another"],
                    "observed_trials": 15,
                    "expected_trials": 15,
                },
                {
                    "eligible": True,
                    "jars": [lab.digest(lab.JAR)],
                    "observed_trials": 1,
                    "expected_trials": 15,
                },
            ):
                self.assertEqual(
                    rollout.enable({}, "rb0_product1", 1, "2031-02-02", 1, g), "BLOCKED"
                )
            sql.assert_not_called()

    def test_timed_out_child_is_reaped(self):
        with subprocess.Popen(
            [sys.executable, "-c", "import time;time.sleep(60)"]
        ) as proc:
            stop_process(proc)
            self.assertIsNotNone(proc.returncode)


if __name__ == "__main__":
    unittest.main()
