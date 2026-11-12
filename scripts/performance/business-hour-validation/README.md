# 영업시간 변경 검증 before/after 도구

> 2026-11-13 B 분리 검증: 별도 전체 build 1회에서 97클래스 1,097건이 실패·오류·skip 0으로 통과했다. 이 도구의 별도 benchmark 컴파일·성능 측정은 새로 실행하지 않았다. 아래 성능 명령은 같은 PC에서 다른 큰 테스트가 돌지 않을 때 사용한다. 과거 성능과 이번 검증 근거는 [분리 기록](../../../work-log/2026-11-13-business-hours-pr-split.md)을 참고한다.

분리본 비교 기준은 `4ac98d2034dfe17d80836667480c7d38459479ab`다. `git archive`로 자기 작업 디렉터리의 `build/business-hour-benchmark/baseline`에 추출하며, 원본 checkout이나 다른 워크트리는 변경하지 않는다. 같은 `BusinessHourValidationFixture`와 benchmark 클래스를 양쪽에 사용한다. 일반 `test` task에는 benchmark 소스가 들어가지 않는다.

## 실행 단계

성능 측정 전에 도구를 준비·컴파일한다. 이번 분리 검증의 일반 build에는 benchmark 소스가 포함되지 않으며 아래 명령을 실행하지 않았다.

```sh
python3 scripts/performance/business-hour-validation/run.py prepare
python3 scripts/performance/business-hour-validation/run.py compile
```

Gradle 명령은 `--offline`을 사용한다. 필요한 의존성이 캐시에 없으면 다운로드로 우회하지 않고 실패를 보고한다. 같은 PC에서 다른 큰 테스트가 끝난 것을 확인한 뒤 다음 명령을 **순서대로** 실행한다. `B_PERF_SLOT_APPROVED=1`은 그 확인을 마쳤다는 표시이며 없으면 `run`이 거부된다. 두 variant를 동시에 실행하지 않는다.

```sh
B_PERF_SLOT_APPROVED=1 python3 scripts/performance/business-hour-validation/run.py run --variant before
B_PERF_SLOT_APPROVED=1 python3 scripts/performance/business-hour-validation/run.py run --variant after
python3 scripts/performance/business-hour-validation/run.py summarize
```

기본값은 활성 예약 `0,10,1000`, 조건별 워밍업 `10`, 측정 `100`, 반복 `3`회다. `B_BENCH_SIZES`, `B_BENCH_WARMUP`, `B_BENCH_SAMPLES`, `B_BENCH_REPEATS`로 조정할 수 있지만 before/after에 같은 값을 사용해야 한다. 2차 시작 시 `0`건·워밍업 `0`·표본 `1`·반복 `1`로 계측 런타임을 먼저 확인할 수 있다. 이 smoke 결과는 별도 보존하고 본 측정에 섞지 않는다. raw 파일이 있으면 도구는 덮어쓰지 않고 종료하므로 이전 결과를 먼저 다른 폴더에 보존한다.

2026-11-11의 기준 `a44952e37993e5003c860714f126057cd311dba5`에서 수행한 컴파일·smoke·본 측정은 [과거 작업 기록](../../../work-log/2026-11-11-business-hour-validation.md)에 남겼다. 그 기록의 raw·JUnit·환경 증거는 당시 로컬 `build` 경로에 있으며 이 PR에 포함하지 않는다. 이 분리본의 통과·성능 근거로 대체하지 않는다.

## 조건과 자원

- 분리본 전용 PostgreSQL 16: 컨테이너 `ct-pr-b-20261113`, 볼륨 `ct-pr-b-20261113-data`, `localhost:55442/ct_pr_b`. 일회용 테스트 계정 `ct_pr_b`와 공개 로컬 전용 기본값 `ct_pr_b_local_only`를 사용한다. 스크립트는 이 대상 외 DB 환경변수를 사용하지 않는다. 2026-11-13 분리본 전체 build는 이 컨테이너에서 실행했고, 이 도구의 성능 명령은 여기서 실행하지 않았다.
- Java 21, Gradle `--no-daemon --max-workers=2`, 측정 JVM `-Xms512m -Xmx512m -XX:ActiveProcessorCount=2`, Hikari 6개 연결.
- 고정 Clock `2030-01-07T03:00:00Z`, 매장 시간대 `Asia/Seoul`, `ddl-auto=validate`, 동일 V1~V35 Flyway.
- 요일 설정 7행(08~22시), 활성 예약 N건을 14일에 분산(10~12시)하고 각 예약에 별도 테이블을 배정한다. 경쟁 요청은 비어 있는 첫 테이블에 14~16시 예약을 생성한다. 따라서 경쟁 예약 자체의 시간/테이블 충돌을 측정에 섞지 않는다.
- 요일 교체(09~21시), 특정일 신규 설정(09~21시), 특정일 삭제(요일 기본값 복원)를 각각 단독/예약 생성 경합 조건으로 측정한다. 매 표본 직전 설정과 경쟁 예약을 JDBC로 복원하며 복원/fixture 생성은 계측 밖이다.
- 예약 생성은 writer가 실제 매장 잠금 SQL을 완료한 순간 시작 신호를 받는다. writer를 인위적으로 지연시키지 않으므로 짧은 요청은 예약 연결이 잠금 SQL을 실행하기 전에 끝날 수 있다. 대기 관측 여부를 함께 읽는다.
- 예약/설정은 실제 서비스 및 Spring 트랜잭션을 사용한다. HTTP 서버는 띄우지 않으므로 **서비스 호출부터 커밋까지의 시간**이며 HTTP 응답 지연은 아니다. 앱 18092/fake HTTP 19092만 설정해 두며 실제 Toss/SOLAPI는 호출하지 않는다. `benchmark-fake-key`는 외부 인증에 사용할 수 없는 테스트 문자열이다.

## 지표 해석

| raw 컬럼 | 뜻 |
| --- | --- |
| `request_ms` | 서비스 proxy 호출 시작부터 성공/예외 반환까지, 트랜잭션 커밋 포함 |
| `sql_count` | 해당 요청 스레드에서 JDBC PreparedStatement execute를 호출한 횟수. commit/rollback·관측 연결·fixture SQL 제외 |
| `config_selects` | 위 SQL 중 business_hour/special_business_day SELECT 횟수. 설정을 찾는 기존 조회와 응답 조회도 포함하므로 검증만의 2회와 구분 |
| `lock_acquire_ms` | 매장 `FOR SHARE`/`FOR NO KEY UPDATE`/`FOR UPDATE` SQL의 JDBC execute 시간. DB 잠금 획득 대기와 실행/통신 시간이 합쳐진 값 |
| `lock_hold_ms` | 매장 잠금 SQL 반환부터 JDBC commit/rollback 반환까지. JDBC 관측 기준 보유 시간 근사치이며 잠금 획득 대기는 제외 |
| `observed_lock_wait_ms`, `wait_samples` | 별도 연결이 `pg_stat_activity.wait_event_type='Lock'`를 약 2ms 간격으로 관측한 예약 연결 대기 구간의 합/양성 표본 수. 샘플 사이의 짧은 대기는 놓칠 수 있음 |
| `pid` | 실제 PostgreSQL backend PID. 경합의 두 요청이 서로 다른 연결인지 assertion으로 확인 |
| `error` | 요청 예외 클래스. 오류가 발생하면 raw를 남기고 도구가 실패하므로 오류율은 실제 수집 표본 기준으로만 해석 |

`lock_acquire_ms`를 순수 DB 대기 시간으로 부르지 않는다. `lock_hold_ms`를 전체 요청 시간으로 부르지 않는다. 관측 쿼리도 DB 부하를 추가하며 같은 호스트의 다른 작업, OS 스케줄링, JIT/GC 및 첫 variant의 캐시 효과가 남는다. 단독/경합을 분리하고 반복별 결과도 비교한다. 100개 미만의 표본으로 p99의 안정성을 주장하지 않는다.

## 산출물

`build/business-hour-benchmark/`에 다음 파일을 남긴다.

- `{before,after}-raw.csv`: 표본별 원시값.
- `{before,after}-environment.json`: 기준 SHA, 현재 HEAD, 모든 main 소스·fixture·도구의 SHA-256, Java/DB 버전, 설정, 표본 수.
- `{before,after}-businessHourBenchmark.log`: 실제 Gradle 명령 결과.
- `summary.json`: variant·작업·규모·경합·actor·반복별/전체 p50/p95/p99, 오류 수/오류율, 대기 관측 요청 수(최근접 순위 방식).
- 각 variant의 `build/test-results/businessHourBenchmark` / `build/reports/tests/businessHourBenchmark`: JUnit 결과.

반복 실행 전 raw와 환경 파일을 한 묶음으로 보존한다. `build` 안의 baseline·결과는 `clean`으로 삭제될 수 있으므로 필요한 비교 결과는 따로 보존한다. 전체 통합 성능이나 HTTP 부하 측정으로 일반화하지 않는다.
