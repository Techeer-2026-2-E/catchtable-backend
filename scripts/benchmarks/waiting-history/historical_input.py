"""File-only admission for the preserved V35 baseline / V36 C batch pair."""
import hashlib
import json
from pathlib import Path, PurePosixPath

BASE = "a44952e37993e5003c860714f126057cd311dba5"
HARNESS = "src/test/java/com/catchtable/waiting/benchmark/WaitingHistoryBenchmark.java"
# Canonical files_sha256 maps from the 2026-11-11 batch-validation snapshots.
# SHA256(json.dumps(map, sort_keys=True, separators=(',', ':')).encode()).
PINNED_MAPS = {
    "baseline": (316, "f48761504294621f7a8050e75e212bce85396fac7e1ca857cdef71267490e1af"),
    "modified": (322, "4720bf7da2ae14d9a17d283532a3ebf10d911144d7b9f163fbc6603a096d503b"),
}
ROOTS = ("build.gradle", "settings.gradle", "gradlew", "gradle", "src/main", "src/test/resources", "src/test/java")


def validate_snapshot(source, variant):
    """Return validated manifest and bytes; never create output or resolve a runtime."""
    source = Path(source)
    if variant not in PINNED_MAPS:
        raise ValueError("Historical variant must be baseline or modified")
    if not source.is_dir() or source.is_symlink():
        raise ValueError("Use a regular historical snapshot directory with manifest.json")
    if ((source / "src/main/java/com/catchtable/notification/durability/WaitingSseJournal.java").exists()
            or list((source / "src/main/resources/db/migration").glob("V3[89]__*.sql"))):
        raise ValueError("Current durable SSE input is unsupported by this historical C tool; "
                         "use the current verification paths in README")
    manifest_path = source / "manifest.json"
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise ValueError("A regular manifest.json is required; symlinks are not allowed")
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, dict):
        raise ValueError("manifest.json must contain an object")
    if (manifest.get("variant") != variant or manifest.get("base") != BASE
            or manifest.get("head") != BASE):
        raise ValueError("Historical variant/base mismatch")
    files = manifest["files_sha256"]
    if not isinstance(files, dict):
        raise ValueError("files_sha256 must be a path/SHA256 map")
    for name in files:
        path = PurePosixPath(name)
        if (path.is_absolute() or path.as_posix() != name or ".." in path.parts
                or not any(name == root or name.startswith(root + "/") for root in ROOTS)):
            raise ValueError(f"Unsafe or non-benchmark input path: {name}")
    count, expected = PINNED_MAPS[variant]
    digest = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if len(files) != count or digest != expected:
        raise ValueError("Historical C input required: pinned V35/V36 batch files_sha256 mismatch; "
                         "current durable SSE is unsupported. See README and docs/waiting-sse-durability.md")
    selected = set()
    for root in ROOTS:
        path = source / root
        for parent in [path, *path.parents]:
            if parent == source:
                break
            if parent.is_symlink():
                raise ValueError(f"Symlink input is not allowed: {parent}")
        if not path.exists():
            raise ValueError(f"Missing historical input: {root}")
        for entry in ([path] if path.is_file() else path.rglob("*")):
            if entry.is_symlink() or not (entry.is_file() or entry.is_dir()):
                raise ValueError(f"Only regular files/directories are allowed: {entry}")
            if entry.is_file():
                selected.add(entry.relative_to(source).as_posix())
    if selected != set(files):
        raise ValueError("Historical input has extra or missing source files; use an intact pinned snapshot")
    payload = {}
    for name, expected_sha in files.items():
        data = (source / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected_sha:
            raise ValueError(f"Historical input changed: {name}")
        payload[name] = data
    return manifest, payload
