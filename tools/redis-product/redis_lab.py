"""Owned product integration lab. Reuse the verified PG/app harness from the local baseline."""

import argparse
import ipaddress
import json
import os
from pathlib import Path
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
from harness import lab as L


def docker(*args, data=None, env=None):
    return L.docker(*args, data=data, env=env)


def runtime():
    return json.loads((L.OUT / "redis-product.json").read_text())


def auth():
    return (L.OUT / ".redis-password").read_text()


def cli(name, *args):
    r = runtime()
    if name not in r["nodes"]:
        raise ValueError(name)
    c = r["nodes"][name]
    verify(c, r["owner"])
    env = dict(os.environ, REDISCLI_AUTH=auth())
    return docker(
        "exec",
        "-e",
        "REDISCLI_AUTH",
        c,
        "redis-cli",
        "--no-auth-warning",
        "--raw",
        "-p",
        "26379" if name.startswith("s") else "6379",
        *args,
        env=env,
    )


def verify(c, owner):
    if (
        docker("inspect", "--format", '{{index .Config.Labels "ct.product.redis"}}', c)
        != owner
    ):
        raise RuntimeError("Wrong Redis container")


def power(name, on):
    r = runtime()
    cid = r["nodes"][name]
    verify(cid, r["owner"])
    docker("start" if on else "stop", cid)


def init():
    lab = L.state()
    L.inspect_db(lab)
    image = docker(
        "image",
        "inspect",
        os.environ.get("CT_REDIS_IMAGE", "redis:7-alpine"),
        "--format",
        "{{.Id}}",
    )
    owner = lab["tag"] + "-" + secrets.token_hex(3)
    nodes = {}
    if (L.OUT / "redis-product.json").exists():
        raise RuntimeError("Redis lab already created")
    names = {
        k: owner + "-" + k for k in ["cache", "master", "replica", "s1", "s2", "s3"]
    }
    pw = secrets.token_hex(24)
    with os.fdopen(
        os.open(L.OUT / ".redis-password", os.O_WRONLY | os.O_EXCL | os.O_CREAT, 0o600),
        "w",
    ) as f:
        f.write(pw)
    env = dict(os.environ, CT_REDIS_PASSWORD=pw)
    for index, name in enumerate(names):
        opts = [
            "run",
            "-d",
            "--name",
            names[name],
            "--network",
            lab["net"],
            "--label",
            "ct.product.redis=" + owner,
            "--memory",
            "160m",
            "--cpus",
            "1",
        ]
        if "subnet" in lab:
            opts += ["--ip", str(ipaddress.ip_network(lab["subnet"])[10 + index])]
        role = (
            "sentinel"
            if name.startswith("s")
            else "primary"
            if name == "master"
            else name
        )
        script = ROOT / "infra/redis/node.sh"
        nodes[name] = docker(
            *opts,
            "--user",
            "redis",
            "-v",
            str(script) + ":/opt/ct-node.sh:ro",
            "-e",
            "CT_REDIS_PASSWORD",
            "-e",
            "CT_REDIS_ROLE=" + role,
            "-e",
            "CT_REDIS_BIND=0.0.0.0",
            "-e",
            "CT_REDIS_ANNOUNCE=" + names[name],
            "-e",
            "CT_REDIS_INITIAL_MASTER=" + names["master"],
            "-e",
            "CT_SENTINEL_DOWN_MS=1200",
            "-e",
            "CT_SENTINEL_FAILOVER_MS=10000",
            image,
            "sh",
            "/opt/ct-node.sh",
            env=env,
        )
        L.save(
            L.OUT / "redis-product.json",
            {"owner": owner, "nodes": nodes, "names": names, "image": image},
        )
    for _ in range(100):
        try:
            if "master_link_status:up" in cli(
                "replica", "INFO", "replication"
            ) and "OK" in cli("s1", "SENTINEL", "CKQUORUM", "ct-waiting"):
                break
        except subprocess.CalledProcessError:
            pass
        time.sleep(0.2)
    else:
        raise RuntimeError("Initial replication/Sentinel quorum not ready")
    print(
        "Redis cache, queue master/replica and three Sentinels ready", owner, flush=True
    )


def settings(cache=True, queue=True, immediate=True, async_http=False, poll=2):
    if "CT_PRODUCT_REDIS_ENDPOINTS" in os.environ:
        endpoints = json.loads(
            Path(os.environ["CT_PRODUCT_REDIS_ENDPOINTS"]).read_text()
        )
        cache_uri = endpoints["cache_uri"]
        queue_uri = endpoints["queue_uri"]
        pw = endpoints["sentinel_password"]
    else:
        n = runtime()["names"]
        pw = auth()
        cache_uri = f"redis://{pw}@{n['cache']}:6379/0"
        seeds = ",".join(n[k] + ":26379" for k in ("s1", "s2", "s3"))
        queue_uri = f"redis-sentinel://{pw}@{seeds}/0?sentinelMasterId=ct-waiting"
    return {
        "availability": {
            "cache": {
                "enabled": cache,
                "uri": cache_uri,
                "db-permits": 1,
                "timeout": "200ms",
            }
        },
        "waiting": {
            "intake": {
                "batch": {"enabled": True, "size": 32, "window-ms": 2},
                "async": {"enabled": async_http},
            },
            "dispatch": {
                "enabled": queue,
                "immediate": immediate,
                "poll-hint-seconds": poll,
                "uri": queue_uri,
                "sentinel-password": pw,
                "timeout": "500ms",
                "lease-ms": 2000,
                "reconcile-ms": 200,
                "poll-ms": 15,
            },
        },
    }


def stop():
    for name in ("s3", "s2", "s1", "replica", "master", "cache"):
        power(name, False)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=("init", "stop"))
    args = ap.parse_args()
    with L.lock():
        (init if args.action == "init" else stop)()
