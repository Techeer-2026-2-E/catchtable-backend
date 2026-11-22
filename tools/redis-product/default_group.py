"""Issue a real restaurant number using the application's unmodified default Sentinel URI."""

import contextlib
import subprocess
import json
import os
import re
import time

import redis_lab as R
from check import check, post, receipt, until

L = R.L


def verify(silent_first=False):
    lab = L.state()
    L.validate_fixture_date(lab)
    db = "rb0_product" + str(time.time_ns())
    folder = L.OUT / (db + ("-sentinel-fallback" if silent_first else "-default-group"))
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE}")
    group = re.search(
        r"^sentinel monitor ([A-Za-z0-9_-]+) ",
        (L.ROOT / "infra/redis/node.sh").read_text(),
        re.M,
    )[1]
    settings = {"waiting": {"dispatch": {"enabled": True}}}
    if silent_first:
        settings["waiting"]["dispatch"].update(
            uri=f"redis-sentinel://127.0.0.1:26378,127.0.0.1:26379/0?sentinelMasterId={group}",
            timeout="250ms",
        )
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(settings)
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
            if silent_first:
                containers.append(
                    L.docker(
                        *common,
                        "--entrypoint",
                        "sh",
                        image,
                        "-c",
                        "while true; do nc -l -p 26378 >/dev/null; done",
                    )
                )
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

            def listening():
                try:
                    return L.docker(
                        "exec",
                        containers[-1],
                        "redis-cli",
                        "-p",
                        "26379",
                        "--raw",
                        "SENTINEL",
                        "get-master-addr-by-name",
                        group,
                    ).splitlines() == ["127.0.0.1", "6379"]
                except subprocess.CalledProcessError:
                    return False

            until(listening, bool)
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
                    "uri_override": silent_first,
                    "silent_first_seed": silent_first,
                    "http": code,
                    "actual_number": 1,
                },
            )
            print(folder.name, group, code, final["waitNumber"], flush=True)
        finally:

            def remove(cid):
                check(
                    L.docker(
                        "inspect",
                        "--format",
                        '{{index .Config.Labels "ct.default.group"}}',
                        cid,
                    ),
                    label,
                )
                L.docker("rm", "-f", "-v", cid)

            with contextlib.ExitStack() as cleanup:
                for cid in containers:
                    cleanup.callback(remove, cid)


if __name__ == "__main__":
    with L.lock():
        verify()
        verify(silent_first=True)
