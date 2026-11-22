"""Verify Sentinel across three owned Lima guests and drive the normal customer API.

The manifest records three new guests in cache / initial primary / replica order,
their local private addresses and boot IDs. Front the isolated application's
three loopback ports locally before running this check.
"""

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

from check import check, http, post, receipt, until


def run(manifest, password_file, ports, date, record, peer_addresses=False):
    topology = json.loads(manifest.read_text())
    names, ips, boot = (topology[k] for k in ("names", "vz", "boot_ids"))
    if not (len(set(names)) == len(set(ips)) == len(set(boot)) == len(names) == 3):
        raise ValueError(
            "expected three distinct test VMs, addresses and boot identities"
        )
    if any(
        not re.fullmatch(
            r"(?:ctr49-[a-f0-9]{6}-[abc]|ctfix-[a-f0-9]{6}-(?:cache|primary|replica))",
            n,
        )
        for n in names
    ):
        raise ValueError("unexpected lab VM name")
    if any(not ipaddress.ip_address(ip).is_private for ip in ips):
        raise ValueError("only private lab addresses allowed")
    if peer_addresses:
        ips = topology["peer"]
        if len(set(ips)) != 3 or any(
            not ipaddress.ip_address(ip).is_private for ip in ips
        ):
            raise ValueError("Expected three private peer addresses")
    secret = password_file.read_text().strip()
    env = dict(os.environ, REDISCLI_AUTH=secret)
    phases = {}

    def save(key, value):
        phases[key] = value
        record.write_text(json.dumps(phases, indent=2) + "\n")
        print(key, value, flush=True)

    def guest_boot(i):
        return subprocess.check_output(
            [
                "limactl",
                "shell",
                "--workdir=/tmp",
                names[i],
                "--",
                "cat",
                "/proc/sys/kernel/random/boot_id",
            ],
            text=True,
        ).strip()

    def rd(i, port, *cmd):
        try:
            result = subprocess.run(
                ["redis-cli", "--raw", "-h", ips[i], "-p", str(port), *cmd],
                env=env,
                text=True,
                timeout=2,
                capture_output=True,
            )
            return result.stdout.strip() if result.returncode == 0 else ""
        except subprocess.TimeoutExpired:
            return ""

    def primary(i, others):
        expected = (
            [names[i] + ".test", "6379"],
            [topology.get("peer", ips)[i], "6379"],
        )
        return all(
            rd(
                s, 26379, "SENTINEL", "get-master-addr-by-name", "ct-waiting"
            ).splitlines()
            in expected
            and rd(s, 26379, "SENTINEL", "CKQUORUM", "ct-waiting").startswith("OK")
            for s in others
        ) and "role:master" in rd(i, 6379, "INFO", "replication")

    def apply(member, app):
        code, body, loc, _ = post(ports[app], member)
        assert code in (201, 202) and loc, (code, body)
        return code, body, loc

    def number(member, loc):
        return until(
            lambda: receipt(ports[2], member, loc),
            lambda x: x["status"] == "REGISTERED",
            40,
        )

    def cached_slot_count():
        for p in ports:
            code, body, *_ = http(
                p, "GET", f"/api/user/stores/1/availability?date={date}&partySize=2", 4
            )
            check(code, 200)
            check(len(body["slots"]), 44)

    def bypasses():
        return sum(
            next(
                (
                    s["value"]
                    for s in (
                        http(
                            p, "GET", "/actuator/metrics/availability.cache.bypass", 4
                        )[1]
                        or {}
                    ).get("measurements", [])
                ),
                0,
            )
            for p in ports
        )

    def off(i):
        check(guest_boot(i), boot[i])
        subprocess.run(
            ["limactl", "stop", "--force", names[i]],
            check=True,
            stdout=subprocess.DEVNULL,
        )

    def on(i):
        subprocess.run(
            ["limactl", "start", "--tty=false", "--timeout=3m", names[i]],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        current = guest_boot(i)
        assert current != boot[i]
        return current

    check([guest_boot(i) for i in range(3)], boot)
    until(lambda: primary(1, (0, 1, 2)), bool, 45)
    until(
        lambda: rd(2, 6379, "INFO", "replication"),
        lambda s: "master_link_status:up" in s,
    )
    cache_key = "ct:vm:cache:" + uuid.uuid4().hex
    queue_key = "ct:vm:queue:" + uuid.uuid4().hex
    check(rd(0, 6379, "SET", cache_key, "volatile"), "OK")
    check(rd(1, 6379, "SET", queue_key, "replicated"), "OK")
    until(lambda: rd(2, 6379, "GET", queue_key), lambda s: s == "replicated")
    for node in (1, 2):
        check(
            rd(node, 6379, "CONFIG", "GET", "appendfsync").splitlines()[-1], "everysec"
        )
    cached_slot_count()
    _, first, loc = apply(600, 0)
    check(number(600, loc)["waitNumber"], 1)
    save(
        "start",
        {"distinct_guest_boot_ids": len(set(boot)), "sentinels": 3, "apps": len(ports)},
    )
    tick = time.monotonic()
    try:
        off(1)
        entries = [apply(601, 1), apply(602, 2)]
        until(lambda: primary(2, (0, 2)), bool, 65)
        elected = time.monotonic() - tick
        check(rd(2, 6379, "GET", queue_key), "replicated")
        bills = [number(i, r[2]) for i, r in zip((601, 602), entries)]
        check([b["waitNumber"] for b in bills], [2, 3])
        check([b["intakeSequence"] for b in bills], [2, 3])
        check(post(ports[0], 601)[1]["receiptId"], entries[0][1]["receiptId"])
        save(
            "primary_guest_loss",
            {
                "surviving_votes": 2,
                "promoted_guest": "C",
                "first_http": [r[0] for r in entries],
                "actual_numbers": [2, 3],
                "promotion_observed_s": round(elected, 3),
                "numbers_observed_s": round(time.monotonic() - tick, 3),
                "replicated_marker_found": True,
            },
        )
    finally:
        new_b = on(1)
    until(
        lambda: rd(1, 6379, "INFO", "replication"),
        lambda s: "role:slave" in s and "master_link_status:up" in s,
        65,
    )
    until(lambda: primary(2, (0, 1, 2)), bool, 65)
    save("primary_rejoined", {"new_kernel_boot": new_b != boot[1], "role": "replica"})
    before = bypasses()
    try:
        off(0)
        cached_slot_count()
        _, _, loc = apply(603, 0)
        check(number(603, loc)["waitNumber"], 4)
        until(lambda: primary(2, (1, 2)), bool, 30)
        delta = bypasses() - before
        assert delta >= 1, delta
        save(
            "cache_guest_loss",
            {"db_bypasses": delta, "new_wait_number": 4, "surviving_votes": 2},
        )
    finally:
        new_a = on(0)
    until(lambda: rd(0, 6379, "PING"), lambda s: s == "PONG", 45)
    check(rd(0, 6379, "EXISTS", cache_key), "0")
    until(lambda: primary(2, (0, 1, 2)), bool, 50)
    check(
        http(
            ports[1], "DELETE", "/api/user/waitings/receipts/" + first["receiptId"], 600
        )[1]["status"],
        "CANCELED",
    )
    cached_slot_count()
    save(
        "complete",
        {
            "cache_rebooted": new_a != boot[0],
            "empty_cache_recovered": True,
            "final_quorum": 3,
            "original_receipt_cancelled": True,
        },
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--ports", nargs=3, type=int, required=True)
    parser.add_argument("--record", type=Path, required=True)
    parser.add_argument("--peer-addresses", action="store_true")
    args = parser.parse_args()
    if args.record.exists():
        raise SystemExit("use a new evidence path")
    run(
        args.manifest,
        args.password_file,
        args.ports,
        args.date,
        args.record,
        args.peer_addresses,
    )
