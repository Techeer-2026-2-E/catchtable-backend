# 과거 V36 C batch 이력 비용 비교 도구

2026-11-12 보완. 이 도구는 **2026-11-11의 고정 V35 baseline / V36 C batch 입력 전용**이다. 현재 V38/V39 영속 SSE 소스를 측정하는 도구가 아니다. 옛 `WaitingSseObservationRecorder`와 START/FINISH 관찰 수, 양쪽 `application.yml` 바이트 동등성이 당시 비교 계약이다. Java harness와 분석기의 단언은 그대로 보존한다. 현행 소스는 `prepare.py`와 `run.py`가 출력 폴더·잠금·DB 변경 전에 거절한다.

당시 C batch 고정본에서 최대64행 저장 보완 후294개 회귀와 smoke/본 측정이 통과했다. 본 표본 SSE31,200행/전체35,400행 및 warmup 포함38,940행이 저장됐고 경고·누락0, 전체 p95 9.326→10.478ms(+12.35%)였다. 이는 0연결의 best-effort 관찰 writer 비용이며 현재 영속 SSE의 성능·고객 수신 보장이 아니다. 이전 손실 결과와 아래 기록도 당시 증거로 보존한다. 자세한 경계는 [C 작업 기록](../../../work-log/2026-11-11-waiting-event-history.md)과 [이번 보완 기록](../../../work-log/2026-11-12-backend-review-docs-tools.md)을 따른다.

현재 전달 구조·수용 제한·UNKNOWN은 [웨이팅 SSE 영속 전달 원장](../../../docs/waiting-sse-durability.md), 현행 V37→V39 upgrade 및 PG/SSE restart 경로는 [통합 검증 README](../../backend-improvement-integration/README.md)의 `verify_sse.py`, 별도 현행 측정 도구는 [SSE throughput README](../../waiting-sse-throughput/README.md)를 참고한다. 각 런타임 실행에는 별도 전용 자원 배정이 필요하며 이번 문서 보완에서는 실행하지 않았다.

아래 분석기는 **보존한 옛 결과**의 파일 검산에 사용한다.

```sh
python3 scripts/benchmarks/waiting-history/analyze.py <baseline-result> <modified-result> <new-comparison.json> --require-complete-observations
```

이 옵션은 표본/워밍업 START·FINISH 각각 기대 수와 총 행 수, 큐 거부·쓰기 실패·종료 미확인 여부를 확인한다. 정상 인자 파싱 뒤 검산에 실패하면 쓰기 가능한 출력 경로에 `status=FAILED` 진단 JSON을 남기고 nonzero로 종료한다. 비교 가능한 부분은 `partial_comparison`에만 두며 성공 결과로 승인하지 않는다. 원시 입력은 수정하지 않는다. batch 실패 후 개별 재시도 경고도 별도로 집계한다. 정상10웨이팅의 4요청 주기는52관찰이며 기대 표본31,200을 줄이지 않는다.

보완 전 1차 성능 실행(원본 보존): 2026-11-11 관리자 독점 실행 배정 후 baseline/modified smoke와 본 측정을 **순차 실행**했다. 양쪽 본 표본 2,400건씩 HTTP 오류는 0이지만 수정본은 SSE 관찰 기록 31,200건 중 26,099건(83.65%)이 큐 포화로 누락됐다. 전체 p95는 10.417→11.986ms(+15.1%)이며 **유실 없는 기록 비용이나 성능 수용 판정이 아니다**. 상세 수치·원본 경로·한계는 [작업 기록](../../../work-log/2026-11-11-waiting-event-history.md)의 2차 측정을 참고한다.

## 고정 입력 선택과 파일 사전검사

명시적인 **snapshot 디렉터리**가 필요하다. `--historical-snapshot` 생략 시 현재 소스를 복사하지 않고 종료2로 거절한다. 입력은 다음 구조이며, 어느 머신에 옮겨도 같은 경로/SHA map으로 검증한다. 압축본만 있다면 신뢰할 수 있는 보존본을 별도 폴더에 복원한 뒤 지정한다. 이 도구가 임의 archive나 사용자 checkout을 풀거나 덮어쓰지는 않는다.

```text
<preserved-snapshot>/
  manifest.json                 # variant, base, head, files_sha256
  build.gradle, settings.gradle, gradlew, gradle/...
  src/main/...                  # baseline V35 또는 modified V36 C batch
  src/test/resources/...
  src/test/java/com/catchtable/waiting/benchmark/WaitingHistoryBenchmark.java
```

출처는 `batch-validation-1794338520607415000/snapshots.json`이 가리킨 `baseline-1794338662111664000` / `modified-1794338666463115000`이다. 함께 보존된 `source-verification.json`의 canonical map 지문을 도구에 고정했다. 디렉터리 이름과 manifest에 적힌 사용자 절대경로는 검증 기준이 아니다.

| 입력 | manifest의 files_sha256 수 | canonical map SHA256 |
| --- | --- | --- |
| baseline | 316 | `f48761504294621f7a8050e75e212bce85396fac7e1ca857cdef71267490e1af` |
| modified | 322 | `4720bf7da2ae14d9a17d283532a3ebf10d911144d7b9f163fbc6603a096d503b` |

canonical map은 `SHA256(json.dumps(files_sha256, sort_keys=True, separators=(',', ':')).encode())`다. manifest뿐 아니라 실제 선택 파일 전부의 SHA와 파일 집합을 확인한다. 선택 범위의 추가 파일·누락·변조·symlink·안전하지 않은 상대경로는 거절한다. 과거 C 원본의 src 전체416개(main315+test101), 제품 map `30061196b2c0985cefb37c124c0014b25da0fc0fe42223d5518d6ee9d1f12462`와 benchmark snapshot의316/322개는 범위가 다르다. `integration-inputs/20261111-C-final/manifest.json`의29개는 기준 커밋 대비 delta여서 단독 snapshot으로 사용할 수 없다. 필요한 manifest/파일이 없으면 보존본 소유자에게 고정 입력 묶음을 받아야 한다. 현재 소스로 manifest를 새로 만들거나 지문을 바꾸어 우회하지 않는다.

```sh
# 직접 제공받거나 복원한 고정 입력 경로로 바꾼다. 이 두 명령은 파일만 읽는다.
C_BASELINE_SNAPSHOT=/path/to/preserved/baseline-1794338662111664000
C_MODIFIED_SNAPSHOT=/path/to/preserved/modified-1794338666463115000
python3 scripts/benchmarks/waiting-history/prepare.py baseline --historical-snapshot "$C_BASELINE_SNAPSHOT" --check-only
python3 scripts/benchmarks/waiting-history/prepare.py modified --historical-snapshot "$C_MODIFIED_SNAPSHOT" --check-only
```

성공은 `VALID_HISTORICAL_INPUT`이며 측정 성공을 뜻하지 않는다. 실패는 stderr의 `Historical C preflight FAILED` 또는 필수 인자 안내와 nonzero 종료다. 실패 시 출력 snapshot을 만들지 않고 Java/Gradle·Docker·DB를 호출하지 않는다.

## 과거 비교 재현 준비 — 실행 배정 후에만

```sh
python3 scripts/benchmarks/waiting-history/prepare.py baseline --historical-snapshot "$C_BASELINE_SNAPSHOT" --compile
python3 scripts/benchmarks/waiting-history/prepare.py modified --historical-snapshot "$C_MODIFIED_SNAPSHOT" --compile
```

출력은 `build/waiting-event-history/benchmarks/<variant>-<timestamp>`다. 검증한 옛 파일과 당시 harness만 새 폴더로 복사한다. 현재 src/main이나 test-only recorder를 복사하지 않는다. 원본 checkout·추가 worktree·외부 fetch를 사용하지 않는다. `manifest.json`은 고정 파일별 SHA256, 원래 HEAD·입력 경로, 포트, Clock을 보존한다. `--compile` 없이 준비하면 파일 복사만 하며 `--compile`은 `testClasses`만 수행하고 앱·부하를 실행하지 않는다.

`local_runtime.py`는 설치된 Java 21과 기존 Gradle 9.7.1 캐시의 실행 파일만 선택한다. 캐시가 없으면 실패하고 다운로드하지 않는다. Java 21은 macOS의 `/usr/libexec/java_home -v 21`로만 찾고 `JAVA_HOME`은 읽지 않는다. 그래서 `--compile`과 `run.py`는 macOS에서만 실행된다. macOS가 아니면 `JAVA_HOME`을 지정해도 동작하지 않는다. wrapper의 배포본 다운로드 경로를 실행하지 않으며, compile/run 모두 `--offline --no-daemon --max-workers=2 --console=plain -Dorg.gradle.java.installations.auto-download=false`를 쓴다. 외부 공급사 URL은 loopback으로 한정하고 실제 비밀값은 사용하지 않는다.

## 전용 자원

- PostgreSQL 16 컨테이너 `ct-improve-c-20261111`, `127.0.0.1:55433`, DB `ct_improve_c`만 사용한다. runner가 정확한 포트 매핑을 검사한다.
- 앱 `127.0.0.1:18083`, 알림톡 simulation, SSE 구독 연결 0개. 19083은 가짜 공급사 주소로 설정하지만 본 도구는 서버를 실행하거나 공급사 HTTP를 호출하지 않는다.
- `bench_c`만 매 실행 전에 재생성한다. 기존 `public`·`baseline_c` 데이터는 보존한다. fixture TRUNCATE도 `bench_c.member`로 한정한다. search_path는 `bench_c,public`이다.
- SQL은 `pg_stat_statements.track=all`로 트리거 내부 SQL까지 관찰한다. 이 설정 변경·재시작은 배정된 전용 컨테이너만 허용한다. 당시 실행에는 해당 설정이 적용돼 있었으며 재현 전에 전용 자원을 확인해야 한다.

새로 설정해야 할 때만 관리자의 실행 배정 후 다음을 수행한다.

```sh
docker exec ct-improve-c-20261111 psql -U ct_improve_c -d ct_improve_c -c "ALTER SYSTEM SET shared_preload_libraries='pg_stat_statements'"
docker restart ct-improve-c-20261111
docker exec ct-improve-c-20261111 psql -U ct_improve_c -d ct_improve_c -c "ALTER SYSTEM SET pg_stat_statements.track='all'"
docker exec ct-improve-c-20261111 psql -U ct_improve_c -d ct_improve_c -c 'SELECT pg_reload_conf()'
```

## 실행 순서

먼저 **각 snapshot에 순차적으로** 같은 smoke를 실행한다. 새 snapshot의 runtime/응답/SQL/이력 수를 확인한 뒤 본 측정에 진입한다.

```sh
RUN_WAITING_HISTORY_BENCHMARK=true BENCH_RUN_LABEL=smoke \
BENCH_WORKERS=1 BENCH_WAITINGS_PER_STORE=10 BENCH_WARMUP_PER_WORKER=0 \
BENCH_SAMPLES_PER_WORKER=4 BENCH_REPEATS=1 \
python3 scripts/benchmarks/waiting-history/run.py <snapshot>
```

본 측정은 기본값으로 baseline 종료 후 modified를 실행한다. `&` 또는 병렬 실행을 사용하지 않는다.

```sh
RUN_WAITING_HISTORY_BENCHMARK=true BENCH_RUN_LABEL=main python3 scripts/benchmarks/waiting-history/run.py <baseline-snapshot>
RUN_WAITING_HISTORY_BENCHMARK=true BENCH_RUN_LABEL=main python3 scripts/benchmarks/waiting-history/run.py <modified-snapshot>
```

runner는 snapshot SHA 검증, worktree 단위 배타 파일 잠금, loopback 앱 포트 검사 후 실행한다. SHA 검증 대상은 고정 입력 범위(`build.gradle`, `settings.gradle`, `gradlew`, `gradle/`, `src/main`, `src/test/resources`, `src/test/java`)뿐이고, 그 밖의 최상위 항목(예: `buildSrc`, `gradle.properties`)은 검사하지 않은 채 그 폴더에서 Gradle을 실행한다. 따라서 `prepare.py`가 만든 폴더만 지정하고, 전달받은 폴더를 `build/waiting-event-history/benchmarks` 아래에 직접 넣지 않는다. 포트 검사는 SO_REUSEADDR를 사용해 이전 실행 종료 후 소켓의 오탐을 줄이되 살아 있는 listener는 거부한다. `--rerun-tasks`로 실제 실행하며 매번 `results/<label>-<timestamp>`를 새로 만들어 이전 결과를 덮어쓰지 않는다. 반환 코드와 summary 존재도 저장한다.

## 동일 조건과 계측 경계

| 항목 | 기본값 |
| --- | --- |
| Java / JVM | Java 21, Gradle test JVM `-Xmx512m`, locale ko/KR |
| Clock | `2031-01-20T03:00:00Z`, Asia/Seoul |
| DB 연결 풀 | 최대 12 |
| 매장 / 웨이팅 | 작업자별 1매장·10웨이팅, 총 4매장·40개 |
| HTTP 작업자 | 4, 각자 다른 매장의 첫 웨이팅만 순차 변경 |
| 반복 동작 | `/call` → 고객 `/accept` → `/recall` → `/call` |
| 워밍업 / 표본 | 작업자당 20 / 200 HTTP 요청 |
| 반복 | 3, 매 반복 fixture 재생성 |
| 알림 / scheduler | SSE 0연결, simulation, 관련 scheduler 지연·주기를 1시간으로 조정, 환불 재시도는 테스트 설정으로 비활성화 |

fixture의 접수는 SQL seed이며 등록 API 비용은 포함하지 않는다. 4개 요청은 새 호출 두 번, 고객 응답 한 번, 기한 유지 재전송 한 번이다. 워밍업 종료 후 SSE 기록 writer를 drain하고 상태 스냅샷/SQL 통계를 초기화한다. 표본 요청 종료 후 다시 drain하여 **HTTP 지연**과 **남은 기록 처리 시간**을 분리한다. 기록 큐가 비어도 거부돼 없어진 관찰이 복구되는 것은 아니다.

40웨이팅 중 나머지 36개는 순번 브로드캐스트의 fan-out 대상이다. 매장당 10웨이팅인 이 경로의 4요청 한 주기는 업무 사건 4건 + outbox 3건 + SSE 관찰 52건(START/FINISH 각각 26건)을 예상한다. 본 표본 반복당 기대는 11,800건, 워밍업 포함 12,980건이다. 최초 호출 여부/현재 차수는 fixture와 warmup에 따라 달라지고 기대값을 임의 변경하지 않는다.

- 요청별 worker/index/operation/status/millis/응답 본문, 오류 및 워밍업 원본을 보존한다. HTTP 재시도는 하지 않는다.
- 응답 code 200, call/recall count, COMING, 동일 기한, 최종 CALLED·응답 초기화, outbox PENDING/attempt0를 검산한다.
- `sql-N.json`의 `toplevel`로 직접 SQL, V36 trigger INSERT, 내부 FK 확인을 분리한다. 감시 SQL과 BEGIN/COMMIT 등은 별도로 분리한다. 내부 실행 시간/WAL은 상위문과 중첩될 수 있어 합계로 더하지 않는다. 내부 SQL 수는 JDBC round trip 수가 아니다.
- `pg_total_relation_size(waiting_event)`는 table/index/TOAST 포함 실제 크기다. 워밍업 전후 차이와 누적 크기를 구분하며 bloat·페이지 할당 영향을 포함한다.
- 50ms 간격의 pool/lock/heap/CPU/host load/관찰 큐를 기록한다. 짧은 대기는 놓칠 수 있고 macOS free-memory 수치는 available-memory가 아니다.

## 증거와 검산

각 결과 폴더에 `environment.json`, `jvm.json`, `fixtures-N.json`, `warmup-N.json`, `requests-N.json`, `state-before-N.json`, `state-after-N.json`, `resources-N.json`, `sql-N.json`, `summary.json`, `run.log`를 남긴다. `archive_logs.py`는 현재 실행의 JUnit XML을 `junit/`에 복사하고 system-out/err를 `application.log`와 `warnings.json`으로 보존한다. Gradle의 `run.log`에 앱 경고가 모두 있다고 가정하지 않는다. 이전 실행 XML은 시작 시각으로 배제한다.

```sh
python3 scripts/benchmarks/waiting-history/analyze.py <baseline-result> <modified-result> <comparison.json>
```

검산기는 같은 파라미터/JVM/DB/소스 harness, 순차 실행, 모든 응답, 업무/outbox 행 수, 직접+trigger INSERT의 실제 rows와 이벤트 수 일치, 큐 거부 경고와 누락 수 일치를 확인한다. 전체 percentile은 모든 표본을 모아 nearest-rank로 계산하며 반복별 percentile의 평균이 아니다. 데이터가 없거나 검산이 실패하면 통과로 처리하지 않는다. 비교 출력 경로는 새 경로를 지정해 원본을 보존한다.

이번 원본 묶음은 `build/waiting-event-history/stage2-1794336488613412000/`이다. `comparison.json`과 `source-verification.json`, `preservation-check.json`, `final-resources.json`을 시작점으로 사용한다. 실제 실행 코드 사본·수정 전 도구·실패한 포트 사전검사 로그도 보존했다. 본 측정 JUnit은 보존 후 앱 로그를 추출했고, smoke는 별도 JSON/run.log를 보존했지만 JUnit 원본은 본 측정 실행 전 별도로 복사하지 못했다. 이후 runner에는 자동 보존을 추가했다.

한계: 로컬 closed-loop 4작업자의 짧은 3반복이며 같은 호스트의 다른 OS 부하·JIT·캐시·항상 baseline을 먼저 실행한 순서 영향을 제거하지 못했다. SSE 연결/고객 전달/ACK/실제 SOLAPI·결제 PG 성능 또는 운영 용량을 측정하지 않았다. 큐 포화 임계점을 탐색한 시험도 아니다. 누락이 있는 1차 수치나 누락0인 당시 batch 결과로 현재 영속 SSE의 운영 요구를 충족했다고 주장하지 않는다.


## batch 보완 재측정 증거

당시 batch 원본 식별자는 `batch-validation-1794338520607415000`이며 `comparison.json`의 observations_complete=true, `regression-counts.json`의294개, `source-verification.json`의 검증 소스 지문으로 확인한다. 같은 harness에 Spring TransactionExecutionListener 카운터를 양쪽 모두 추가해 표본의 새 Spring commit/rollback을 셌다. JDBC autocommit·Flyway·fixture·warmup은 제외한다. 실제 표본에서 baseline commit3,600 / batch6,026(관찰2,426), 관찰31,200행, batch retry/큐 거부/쓰기 실패/종료 미확인0이다. 이전 실행에는 transaction listener가 없었으므로 시점 간 절대 지연을 직접적인 개선율로 해석하지 않는다.


## 검산 실패 기록 (C-BATCH-SPEC-1)

성공 종료(0)의 JSON 키·통계·완전성 결과는 기존 형식 그대로다. 입력 파일 누락/JSON 오류, describe 내부 assertion, 두 결과 조건 불일치 또는 엄격 완전성 실패는 종료1과 다음 형태의 새 출력 JSON을 남긴다.

```json
{
  "status": "FAILED",
  "inputs": {"baseline": "<baseline-result>", "modified": "<modified-result>"},
  "error": {
    "type": "AssertionError",
    "reason": "writeFailed=1; expected zero observation write failures",
    "stage": "describe.modified",
    "input": "<modified-result>",
    "location": {"file": "analyze.py", "line": 150, "function": "describe"}
  },
  "partial_comparison": {}
}
```

`partial_comparison`에는 실패 전에 계산된 항목만 있을 수 있다. 엄격 모드에서 기존 손실 자료를 거부한 경우에는 계산된 비교 전체와 observations_complete=false가 이 필드에 들어간다. 이 필드는 성공/수용 결과가 아니다. 성공 여부는 **종료 코드와 최상위 FAILED 상태**로 판단하고, 파일이 있다는 이유만으로 성공으로 처리하지 않는다.

결과는 출력 경로와 같은 디렉터리의 임시 파일에 쓴 뒤 원자적으로 교체한다. 재사용한 출력 경로에 이전 성공 JSON이 있더라도 검산 실패 JSON으로 교체하므로 이전 성공이 이번 결과로 남지 않는다. 실제 원본을 보존하려면 각 실행에 새 출력 경로를 지정한다.

보장 범위는 명령행 인자가 정상 파싱되고 출력 부모 디렉터리가 존재하며 쓰기/교체가 가능한 경우다. 출력 경로가 디렉터리이거나 쓰기 불가능하면 JSON 생성/교체를 보장하지 않는다. 이때 종료2와 stderr 진단을 남기며, 기존 파일이 남아도 **이번 실행 결과로 사용하면 안 된다**. 잘못된 명령행 인자도 argparse의 종료2 대상이며 결과 JSON 보장 범위 밖이다. 입력 검산 실패를 성공으로 바꾸거나 기대행을 줄이지 않는다.

파일 기반 검증은 제품/DB 장애 재현이나 새 부하 측정이 아니다. 보존된 작은 smoke 자료를 복사해 경고/실행 상태/입력 파일 오류를 합성하고 원래 손실 자료는 읽기만 한다.

```sh
python3 scripts/benchmarks/waiting-history/verify_analyzer_failures.py \
  --before-analyzer <preserved-before-analyze.py> \
  --smoke-runs <batch-validation>/smoke-runs.json \
  --loss-runs <stage2>/main-runs.json \
  --output-dir <new-verification-directory>
```

검증 디렉터리는 새 경로여야 한다. 각 분석기 명령·종료 코드·stdout/stderr·결과 파일 경로는 verification.json과 개별 로그로 남는다.
이번 검증 원본은 `build/waiting-event-history/analyzer-failure-validation-1794340043296479000/verification-1`이며 분석기13회 실행으로 정상0·검산 실패1·출력 경로 실패2를 확인했다. 정상 JSON은 수정 전과 바이트 단위로 동일했고 기존 손실은 계속 엄격 모드에서 실패했다.
