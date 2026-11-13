"""Preserve this run's JUnit and application logs before another run replaces Gradle output."""
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET


def archive(snapshot, output, not_before=0):
    files = sorted((snapshot / "build/test-results/test").glob("TEST-*WaitingHistoryBenchmark.xml"))
    files = [path for path in files if path.stat().st_mtime >= not_before]
    destination = output / "junit"
    destination.mkdir(exist_ok=True)
    logs = []
    tests = failures = errors = skipped = 0
    for source in files:
        shutil.copy2(source, destination / source.name)
        suite = ET.parse(source).getroot()
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        logs.extend(node.text or "" for node in suite.iter() if node.tag in ("system-out", "system-err"))
    log = "\n".join(logs)
    (output / "application.log").write_text(log)
    warnings = {"queueRejected": log.count("웨이팅 SSE 관찰 큐 포화/종료"),
                "writeFailed": log.count("웨이팅 SSE 관찰 저장 실패"),
                "batchRetried": log.count("웨이팅 SSE 관찰 묶음 저장 실패"),
                "shutdownUnknown": log.count("웨이팅 SSE 관찰 종료 시 저장 결과 미확인"),
                "junitTests": tests, "junitFailures": failures, "junitErrors": errors,
                "junitSkipped": skipped, "junitFiles": len(files)}
    (output / "warnings.json").write_text(json.dumps(warnings, indent=2) + "\n")
    return warnings


if __name__ == "__main__":
    print(json.dumps(archive(Path(sys.argv[1]), Path(sys.argv[2])), indent=2))
