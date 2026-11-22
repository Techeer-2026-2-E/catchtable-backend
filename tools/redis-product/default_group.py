"""Issue a real restaurant number using the application's unmodified default Sentinel URI."""

import json
import os
import re
import time

import redis_lab as R
from check import check, post, receipt, until

L = R.L


def verify():
    lab = L.state()
    db = "rb0_product" + str(time.time_ns())
    folder = L.OUT / (db + "-default-group")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE}")
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(
        {"waiting": {"dispatch": {"enabled": True}}}
    )
    group = re.search(
        r"^sentinel monitor ([A-Za-z0-9_-]+) ",
        (L.ROOT / "infra/redis/node.sh").read_text(),
        re.M,
    )[1]
    image = R.runtime()["image"]
    with L.apps(lab, db, 1, folder) as (ports, ids):
        label = lab["tag"] + "-" + db
        common = [
            "run",
            "-d",
            "--network",
            "container:" + ids[0],
            "--label",
            "ct.default.group=" + label,
            "--user",
            "redis",
            "--memory",
            "96m",
            "--cap-drop",
            "ALL",
        ]
        containers = []
        try:
            # Both listeners are confined to this JVM's loopback network namespace.
            containers.append(
                L.docker(
                    *common,
                    image,
                    "redis-server",
                    "--bind",
                    "127.0.0.1",
                    "--save",
                    "",
                    "--appendonly",
                    "no",
                )
            )
            config = f"bind 127.0.0.1\nprotected-mode yes\nport 26379\nsentinel monitor {group} 127.0.0.1 6379 1\n"
            containers.append(
                L.docker(
                    *common,
                    "-e",
                    "CT_SENTINEL_CONF",
                    "--entrypoint",
                    "sh",
                    image,
                    "-c",
                    'printf "%s" "$CT_SENTINEL_CONF" >/tmp/local-sentinel.conf; exec redis-server /tmp/local-sentinel.conf --sentinel',
                    env=dict(os.environ, CT_SENTINEL_CONF=config),
                )
            )
            until(
                lambda: L.docker(
                    "exec",
                    containers[-1],
                    "redis-cli",
                    "-p",
                    "26379",
                    "--raw",
                    "SENTINEL",
                    "get-master-addr-by-name",
                    group,
                ),
                lambda answer: answer.splitlines() == ["127.0.0.1", "6379"],
            )
            check(
                L.sql(lab, db, "SELECT enable_waiting_intake(1,current_date,2)"),
                "ENABLED",
            )
            code, first, url, _ = post(ports[0], 902)
            assert code in (201, 202)
            final = until(
                lambda: receipt(ports[0], 902, url),
                lambda r: r["status"] == "REGISTERED",
                15,
            )
            check(final["waitNumber"], 1)
            check(final["receiptId"], first["receiptId"])
            L.save(
                folder / "default-group.json",
                {
                    "jar": L.digest(L.JAR),
                    "group": group,
                    "uri_override": False,
                    "http": code,
                    "actual_number": 1,
                },
            )
            print(folder.name, group, code, final["waitNumber"], flush=True)
        finally:
            for cid in reversed(containers):
                check(
                    L.docker(
                        "inspect",
                        "--format",
                        '{{index .Config.Labels "ct.default.group"}}',
                        cid,
                    ),
                    label,
                )
                L.docker("stop", cid)
                L.docker("rm", "-v", cid)


if __name__ == "__main__":
    with L.lock():
        verify()
