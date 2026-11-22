"""Isolated PostgreSQL 16, synthetic data and cgroup-limited product JVMs on local Docker Desktop."""

import contextlib
import fcntl
import hashlib
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
session = os.environ.get("CT_BASELINE_SESSION", "")
if session and not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,48}", session):
    raise ValueError("Invalid CT_BASELINE_SESSION")
OUT = Path(os.environ.get("CT_PRODUCT_RAW", ROOT / "build/intake-lab/raw")).resolve()
DB_PORT = 55486
APP_PORT = 18681
OWNER = "io.catchtable.redis-product"
ROLE = "rb0"
TEMPLATE = "rb0_fixture"
JAR = Path(os.environ.get("CT_PRODUCT_MEASURED_JAR", OUT / "measured.jar")).resolve()


def check(args, *, data=None, env=None):
    return subprocess.run(
        args, input=data, text=True, capture_output=True, check=True, env=env
    ).stdout.strip()


def docker(*args, data=None, env=None):
    return check(
        ["docker", "--context", "desktop-linux", *map(str, args)], data=data, env=env
    )


def save(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def lock():
    OUT.mkdir(parents=True, exist_ok=True)
    handle = (OUT / ".lock").open("a")
    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return handle


def unused_port(port):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", port))
        s.listen(1)


def state():
    return json.loads((OUT / "lab.json").read_text())


def inspect_db(lab):
    # Select only non-secret fields from Docker inspect.
    raw = docker(
        "inspect",
        "--format",
        '{{.Id}} {{index .Config.Labels "io.catchtable.redis-product"}} {{json .HostConfig.PortBindings}}',
        lab["db"],
    )
    cid, label, ports = raw.split(" ", 2)
    expected = {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(DB_PORT)}]}
    if (cid, label, json.loads(ports)) != (lab["id"], lab["tag"], expected):
        raise RuntimeError("Dedicated database identity or loopback binding changed")


def sql(lab, dbname, statement):
    inspect_db(lab)
    if not re.fullmatch(r"rb0(_[a-z0-9_]+)?", dbname):
        raise ValueError(dbname)
    return docker(
        "exec",
        "-i",
        lab["id"],
        "psql",
        "-XqAt",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        ROLE,
        "-d",
        dbname,
        data=statement,
    )


def api(port, path, method="GET", body=None, member=4, timeout=3):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        method=method,
        headers={"Content-Type": "application/json", "X-Member-Id": str(member)},
        data=None if body is None else json.dumps(body).encode(),
    )
    try:
        response = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        response = e
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else {}


def start_app(lab, dbname, count, index):
    inspect_db(lab)
    port = APP_PORT + index
    unused_port(port)
    env = dict(os.environ, DB_PASSWORD=(OUT / ".db-password").read_text())
    opts = {
        "DB_HOST": lab["db"],
        "DB_PORT": "5432",
        "DB_NAME": dbname,
        "DB_USERNAME": ROLE,
        "SPRING_DATASOURCE_URL": f"jdbc:postgresql://{lab['db']}:5432/{dbname}?ApplicationName=rb0-app-{index}",
        "SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE": str(12 // count),
        "SPRING_DATASOURCE_HIKARI_MINIMUMIDLE": str(12 // count),
        "SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT": "5000",
        "SERVER_TOMCAT_THREADS_MAX": str(120 // count),
        "SERVER_TOMCAT_THREADS_MINSPARE": str(12 // count),
        "SERVER_TOMCAT_MAXCONNECTIONS": str(6000 // count),
        "SERVER_TOMCAT_ACCEPTCOUNT": str(600 // count),
        "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE": "health,metrics",
        "SERVER_TOMCAT_ACCESSLOG_ENABLED": "true",
        "SERVER_TOMCAT_ACCESSLOG_DIRECTORY": "/tmp/rb0-access",
        "SERVER_TOMCAT_ACCESSLOG_PATTERN": "%{begin:msec}t %{end:msec}t %{X-Baseline-Id}i %m %U %s %b",
        "TZ": "Asia/Seoul",
        "LOGGING_LEVEL_COM_CATCHTABLE": "WARN",
        "LOGGING_LEVEL_ROOT": "WARN",
        "ALIMTALK_ENABLED": "false",
        "ALIMTALK_MODE": "simulation",
        "TOSS_BASE_URL": "http://127.0.0.1:1",
        "SOLAPI_BASE_URL": "http://127.0.0.1:1",
        "SERVER_SHUTDOWN": "graceful",
    }
    opts["SPRING_APPLICATION_JSON"] = json.dumps(
        {
            "waiting": {
                "intake": {
                    "batch": {
                        "enabled": os.environ.get("BATCH_TRIAL_ENABLED", "false")
                        == "true",
                        "size": int(os.environ.get("BATCH_TRIAL_SIZE", "32")),
                        "window-ms": int(os.environ.get("BATCH_TRIAL_WINDOW", "2")),
                    }
                }
            }
        }
    )
    if "CT_PRODUCT_CONFIG" in os.environ:
        opts["SPRING_APPLICATION_JSON"] = os.environ["CT_PRODUCT_CONFIG"]
    if os.environ.get("CT_TOMCAT_TIMING") == "true":
        opts["SERVER_TOMCAT_ACCESSLOG_PATTERN"] += (
            " %{ctQueued}r %{ctStarted}r %{ctExecUs}r"
        )
    env.update(opts)
    options = [item for k in opts for item in ("-e", k)]
    cid = docker(
        "run",
        "-d",
        "--name",
        f"{lab['tag']}-{dbname}-{index}",
        "--label",
        f"{OWNER}={lab['tag']}",
        "--network",
        lab["net"],
        "--cpus",
        3 // count,
        "--memory",
        f"{1920 // count}m",
        "-p",
        f"127.0.0.1:{port}:8080",
        "--user",
        "1001:1001",
        "-v",
        f"{JAR}:/app.jar:ro",
        *options,
        "-e",
        "DB_PASSWORD",
        lab["jre_image"],
        "java",
        f"-XX:ActiveProcessorCount={3 // count}",
        "-XX:+UseG1GC",
        f"-Xms{768 // count}m",
        f"-Xmx{768 // count}m",
        "-jar",
        "/app.jar",
        env=env,
    )
    return cid


@contextlib.contextmanager
def apps(lab, dbname, count, folder):
    ports = list(range(APP_PORT, APP_PORT + count))
    ids = []
    try:
        for i in range(count):
            ids.append(start_app(lab, dbname, count, i))
        save(
            folder / "app-limits.json",
            [
                json.loads(
                    docker(
                        "inspect",
                        "--format",
                        '{"cpu":{{.HostConfig.NanoCpus}},"memory":{{.HostConfig.Memory}},"args":{{json .Args}}}',
                        cid,
                    )
                )
                for cid in ids
            ],
        )
        for p in ports:
            for _ in range(200):
                try:
                    if api(p, "/actuator/health", timeout=0.6)[0] == 200:
                        break
                except (OSError, ValueError):
                    pass
                if (
                    docker(
                        "inspect", "--format", "{{.State.Running}}", ids[p - APP_PORT]
                    )
                    != "true"
                ):
                    raise RuntimeError(f"JVM failed on {p}")
                time.sleep(0.3)
            else:
                raise RuntimeError(f"JVM readiness timed out on {p}")
        yield ports, ids
    finally:
        # Operate only on IDs returned by this invocation. Flush access logs before copying.
        if ids:
            docker("stop", "--time", "35", *ids)
        for i, cid in enumerate(ids):
            (folder / f"container-{i}.json").write_text(
                docker(
                    "inspect",
                    "--format",
                    '{"exit":{{.State.ExitCode}},"oom":{{.State.OOMKilled}},"id":"{{.Id}}"}',
                    cid,
                )
                + "\n"
            )
            with (folder / f"app-{i}.log").open("w") as output:
                subprocess.run(
                    ["docker", "--context", "desktop-linux", "logs", cid],
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
            target = folder / f"access-{i}"
            result = subprocess.run(
                [
                    "docker",
                    "--context",
                    "desktop-linux",
                    "cp",
                    f"{cid}:/tmp/rb0-access/.",
                    str(target),
                ],
                capture_output=True,
                check=False,
            )
            if result.returncode:
                (folder / f"access-{i}.error").write_bytes(result.stderr)
            docker("rm", cid)


def init():
    if (OUT / "lab.json").exists() or (OUT / ".db-password").exists():
        raise RuntimeError("Lab already exists. Use its recorded identity.")
    endpoint = docker(
        "context", "inspect", "desktop-linux", "--format", "{{.Endpoints.docker.Host}}"
    )
    if not endpoint.startswith("unix://"):
        raise ValueError("Expected a local Docker Desktop socket")
    unused_port(DB_PORT)
    tag = "ct-product-" + secrets.token_hex(4)
    jar = ROOT / "build/libs/catchtable-0.0.1-SNAPSHOT.jar"
    if jar.resolve() != JAR:
        shutil.copyfile(jar, JAR)
    JAR.chmod(0o644)
    lab = {
        "tag": tag,
        "db": tag + "-pg",
        "net": tag + "-net",
        "head": check(["git", "rev-parse", "HEAD"]),
        "jar_sha256": digest(JAR),
        "fixture_sha256": digest(HERE / "fixture.sql"),
        "pg_image": docker("image", "inspect", "postgres:16", "--format", "{{.Id}}"),
        "jre_image": docker(
            "image", "inspect", "eclipse-temurin:21-jre", "--format", "{{.Id}}"
        ),
    }
    generator = str(HERE.parent / "client.go")
    check(["go", "build", "-o", str(OUT / "product-client"), generator])
    arch = docker("image", "inspect", lab["jre_image"], "--format", "{{.Architecture}}")
    check(
        ["go", "build", "-o", str(OUT / "product-client-linux"), generator],
        env=dict(os.environ, CGO_ENABLED="0", GOOS="linux", GOARCH=arch),
    )
    network_ids = docker("network", "ls", "-q").splitlines()
    configs = docker(
        "network", "inspect", "--format", "{{json .IPAM.Config}}", *network_ids
    )
    used = [
        ipaddress.ip_network(entry["Subnet"])
        for line in configs.splitlines()
        for entry in (json.loads(line) or [])
        if entry.get("Subnet")
    ]
    candidates = (ipaddress.ip_network(f"10.231.{i}.0/24") for i in range(256))
    subnet = next(
        n
        for n in candidates
        if all(not n.overlaps(other) for other in used if other.version == 4)
    )
    # Reserve the lower half for stable Redis/Sentinel identities across simultaneous restarts.
    pool = list(subnet.subnets(prefixlen_diff=1))[1]
    docker(
        "network",
        "create",
        "--subnet",
        str(subnet),
        "--ip-range",
        str(pool),
        "--label",
        f"{OWNER}={tag}",
        lab["net"],
    )
    lab["subnet"] = str(subnet)
    password = secrets.token_urlsafe(32)
    with os.fdopen(
        os.open(OUT / ".db-password", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w"
    ) as f:
        f.write(password)
    lab["id"] = docker(
        "run",
        "-d",
        "--name",
        lab["db"],
        "--network",
        lab["net"],
        "--label",
        f"{OWNER}={tag}",
        "--cpus",
        "2",
        "--memory",
        "768m",
        "--shm-size",
        "192m",
        "-p",
        f"127.0.0.1:{DB_PORT}:5432",
        "-e",
        f"POSTGRES_DB={ROLE}",
        "-e",
        f"POSTGRES_USER={ROLE}",
        "-e",
        "POSTGRES_PASSWORD",
        lab["pg_image"],
        "-c",
        "timezone=Asia/Seoul",
        "-c",
        "shared_preload_libraries=pg_stat_statements",
        "-c",
        "pg_stat_statements.track=all",
        "-c",
        "track_io_timing=on",
        "-c",
        "max_connections=60",
        env=dict(os.environ, POSTGRES_PASSWORD=password),
    )
    save(OUT / "lab.json", lab)
    for _ in range(100):
        try:
            if docker(
                "exec",
                lab["id"],
                "pg_isready",
                "-h",
                "127.0.0.1",
                "-U",
                ROLE,
                "-d",
                ROLE,
            ).endswith("accepting connections"):
                break
        except subprocess.CalledProcessError:
            pass
        time.sleep(0.25)
    sql(lab, ROLE, f"CREATE DATABASE {TEMPLATE}; CREATE DATABASE rb0_tests;")
    folder = OUT / "init"
    folder.mkdir()
    with apps(lab, TEMPLATE, 1, folder):
        pass
    sql(lab, TEMPLATE, "CREATE EXTENSION pg_stat_statements;")
    sql(lab, TEMPLATE, (HERE / "fixture.sql").read_text())
    lab.update(
        fixture_date=sql(lab, TEMPLATE, "SELECT current_date;"),
        postgres=sql(lab, TEMPLATE, "SELECT version();"),
        flyway=sql(
            lab,
            TEMPLATE,
            "SELECT max(installed_rank),array_agg(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success;",
        ),
        durability=sql(
            lab,
            TEMPLATE,
            "SELECT current_setting('fsync'),current_setting('synchronous_commit'),current_setting('full_page_writes');",
        ),
        jre=docker("run", "--rm", lab["jre_image"], "java", "--version"),
        go=check(["go", "version"]),
        host_cpus=check(["sysctl", "-n", "hw.logicalcpu"]),
        host_bytes=check(["sysctl", "-n", "hw.memsize"]),
    )
    save(OUT / "lab.json", lab)
    print(lab["db"], lab["postgres"], flush=True)


if __name__ == "__main__":
    with lock():
        if sys.argv[1:] == ["init"]:
            init()
        elif sys.argv[1:] in (["stop"], ["start"]):
            lab = state()
            inspect_db(lab)
            print(docker(sys.argv[1], lab["id"]))
        else:
            raise SystemExit("lab.py init | stop | start")
