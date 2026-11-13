"""Resolve only the already installed Java 21 and cached Gradle distribution."""
import os
from pathlib import Path
import subprocess


def runtime():
    java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    candidates = sorted(cache.glob("wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle"))
    if not candidates:
        raise SystemExit("Gradle 9.7.1 is not cached; downloading is forbidden")
    return java_home, str(candidates[0])


OFFLINE_FLAGS = ["--offline", "--no-daemon", "--max-workers=2", "--console=plain",
                 "-Dorg.gradle.java.installations.auto-download=false"]
