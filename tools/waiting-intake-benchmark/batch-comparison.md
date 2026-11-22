# 단건·일괄 접수 비교 재현

[기존 HTTP 측정기](README.md)와 동일한 제품 경로·fixture·발생기를 사용한다. 이 비교의 앱 포트는 18581~18583, PG 포트는 55485다. 모든 HTTP 대상은 루프백이다. 새 작업트리와 새 실험 폴더에서 실행한다.

```bash
set -e
mkdir -p build/intake-lab/tools
cp "$BASELINE_TOOLS"/{lab.py,run.py,analyze.py,fixture.sql,monitor.sql,client.go,preflight.py} build/intake-lab/tools/
(cd build/intake-lab/tools && patch -p0 < ../../../tools/waiting-intake-benchmark/baseline.patch)
(cd build/intake-lab/tools && patch -p0 < ../../../tools/waiting-intake-benchmark/batch-lab.patch)
./gradlew --offline bootJar
python3 build/intake-lab/tools/lab.py init
cp build/libs/catchtable-0.0.1-SNAPSHOT.jar build/intake-lab/raw/measured.jar
chmod 644 build/intake-lab/raw/measured.jar
python3 - <<'PY'
import sys
sys.path.insert(0,'tools/waiting-intake-benchmark')
from measure import L,product_source
(L.OUT/'measured-source.sha256').write_text(product_source()+'\n')
PY
python3 tools/waiting-intake-benchmark/compare_batch.py smoke
python3 tools/waiting-intake-benchmark/batch_faults.py
python3 tools/waiting-intake-benchmark/compare_batch.py suite
python3 tools/waiting-intake-benchmark/compare_batch.py retry
python3 tools/waiting-intake-benchmark/compare_batch.py report
python3 build/intake-lab/tools/lab.py stop
```

- 모집 대상은 같은 서버에 들어온 같은 매장의 유효한 키를 가진 POST다. 별도 실행기 스레드를 늘리지 않는다. Spring 설정 `waiting.intake.batch.enabled`, `size`, `window-ms`를 비교할 때만 바꾼다. 동일 매장의 선행 그룹이 바쁜 동안은 이미 기다리는 호출을 다음 그룹에 포함할 수 있다. 첫 도착 후 수집을 위해 추가로 기다리는 시간과 DB 처리 차례를 기다리는 시간은 다르다.
- 같은 JAR로 direct와 batch32를 다섯 조건에서 3회씩, group1을 오픈 3대 조건에서 3회 수행한다. 비교군 순서를 교대하며 매 회차 독립 fixture DB를 사용한다. 판정은 모든 유효 신청의 PG 커밋과 첫 202, p95 1초·p99 2초, B매장 무오류 및 종료 후 20초 안 회복 후보다. 입력 창이 부족한 회차는 불충분으로 남긴다.
- `receipts.csv`·`keys.csv`의 `insert_xid`는 최초 삽입된 물리 행의 PostgreSQL `xmin`이다. 신규 고유 사용자만 있는 측정 구간에서는 서로 다른 TX 수, 실제 묶음당 행 수, 표와 최초 키의 원자성을 독립 검산할 수 있다. 이미 종료된 회차를 재시작할 때 `resume`은 완료 회차의 구성·계측·순서를 확인하고 미실행 구간만 이어간다. `gate`는 후보 미달·회차 부족에 종료코드 2를 반환한다.
- `batch_faults.py`는 전용 DB 복제본·컨테이너 ID·소유 라벨을 확인한 뒤 시험용 앱 1개만 종료한다. 업무 쓰기 전과 PG COMMIT 안에서 각각 정지 지점을 만들고, 해당 시점에 202가 없음을 확인한다. PG 상태와 두 요청의 재시도를 대조한다. 인프라 운영 스크립트로 쓸 용도는 없다.
- 이번 Mac mini 캠페인은 현재 파일의 재개 수정본과 수정 전 23회 로그를 모두 보존한다. 원격에서 별도 빌드하지 않고 검증한 arm64 이미지 레이어·설정, JAR, Go 발생기와 소스 해시를 대조해 실행했다. 독립 보고서/검증 아카이브의 재현 디렉터리에 실제 실행기를 넣었다. 관련 검증과 결과는 [작업로그](../../work-log/2026-11-22-waiting-intake-batch.md)에 있다.
