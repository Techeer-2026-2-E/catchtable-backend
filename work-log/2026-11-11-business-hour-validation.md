# 영업시간 변경의 활성 예약 검증 조회 최적화

> 2026-11-13 분리 안내: 아래 내용은 2026-11-11 작업과 당시 검증·성능 기록이다. 당시 로컬 `build`·`artifacts` 증거는 이 PR에 포함하지 않으며, 아래 통과 건수와 성능값은 분리본의 새 실행 결과가 아니다. 이번 분리본의 별도 전체 build는 97클래스 1,097건 실패·오류·skip 0이며 새 성능 측정은 하지 않았다. 현재 범위·실행 근거·남은 검증은 [B 분리 검증 기록](2026-11-13-business-hours-pr-split.md)에 남긴다.

## 범위와 근거

- 기준 HEAD: `a44952e37993e5003c860714f126057cd311dba5`, 로컬 브랜치 `perf/business-hour-validation`.
- 공통 계약의 1차 단계: 구현, 관련 회귀·별도 DB 연결 동시성 검증, 재현 가능한 성능 비교 도구 준비. 부하 측정은 관리자가 배정할 2차 슬롯에서 수행한다.
- 기존 `validateActiveReservationsCovered`는 모든 활성 예약마다 `isOpenBetween`을 호출하고 전날/당일 특정일 및 요일 설정을 다시 읽었다. 설정 변경은 이미 배타 매장 잠금 아래 flush 후 검증했고, 예약 생성은 이미 공유 매장 잠금 아래 검증·저장했다. 이 잠금/롤백 동작을 새 기능으로 보고하지 않는다.
- [Notion 영업시간 관리](https://app.notion.com/p/3d548d76fa3281bfa916f1cf77badc39)의 본문은 2026-11-03 기준으로 특정일 기능이 없다고 적혀 있다(조회 응답의 마지막 편집일 2026-11-07). 기준 코드의 V35·`SpecialBusinessDayService`·테스트에는 이미 구현되어 있다. 이번 작업은 기능을 재구현하거나 원문을 수정하지 않았다.

## 변경

활성 예약을 한 번 읽고, 각 예약의 서울 기준 시작일과 전날을 수집한다. 활성 예약이 있으면 요일 설정 전체(최대 7행)와 필요한 최소~최대 날짜 범위의 특정일 설정을 각 한 번 읽는다. 날짜별 영업 구간 Map을 만들어 검증 한 번 동안 재사용한다. 예약이 없으면 설정 snapshot을 조회하지 않는다.

특정일 설정이 있으면 휴무를 포함해 요일 설정보다 우선한다. 엔티티의 기존 구간 계산을 사용해 브레이크와 자정 넘김 경계를 유지한다. 호출부의 flush 이후 JPA 조회를 사용하므로 `@SQLRestriction`의 soft-delete 제외를 반영한다. snapshot은 메서드 로컬이며 다음 요청/변경에서 재사용하지 않는다.

`replaceBusinessHours`, 특정일 설정·삭제는 모두 기존 공통 검증 메서드를 호출하므로 이 변경을 적용받는다. `SpecialBusinessDayService`와 공개 조회 API는 수정할 필요가 없었다. 배타/공유 잠금, flush와 검증 순서, 예약 상태/활성 조건, 트랜잭션 경계, 웨이팅 검증 및 에러 코드도 유지한다. 마이그레이션·공통 설정·build.gradle 변경은 없다.

신규 저장소 메서드는 단일 매장용 `findAllByStoreIdAndBusinessDateBetween(Long, LocalDate, LocalDate)`다. 날짜 범위가 아주 넓으면 중간의 불필요한 특정일 행도 읽을 수 있고, 모든 활성 예약의 로딩 자체도 남아 있다. 이번 작업은 반복 설정 조회를 줄이는 범위다.

## PR #270과 병합 범위

2026-11-11 GitHub connector 조회에서 [PR #270](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/270)은 open, head `6979a9b0c08a906c047885b910a86d9a49dffd94`, base는 위 기준 HEAD였다. 해당 PR의 실제 `SpecialBusinessDayRepository.java` patch는 `Collection` import와 여러 매장용 `findAllByStoreIdInAndBusinessDateBetween(Collection<Long>, LocalDate, LocalDate)`를 추가한다.

이번 작업과 **같은 파일 끝의 메서드 추가 위치**가 겹친다. 동일 시그니처 중복은 없고 단일/복수 매장 조회가 공존할 수 있다. 병합 시 두 메서드와 #270의 Collection import를 보존해야 한다. PR을 병합하거나 수정하지 않았다. A가 사용하는 `getOpeningWindows`·`isOpenBetween` 공개 계약도 바꾸지 않았다.

## 검증 환경과 결과

전용 신규 컨테이너 `ct-improve-b-20261111`, PostgreSQL **16.15**, `127.0.0.1:55432/ct_improve_b`, Java **21.0.12.1**, Gradle `--no-daemon --max-workers=2`를 사용했다. 기존 `axum-postgres:5375`에는 연결하지 않았다. Flyway V35까지 적용했고 `ddl-auto=validate`를 유지했다. 신규 fixture는 종료 후 전용 매장 잔존 0건을 확인했다.

| 검증 | 실제 결과 |
| --- | --- |
| 최종 관련 회귀 | **13클래스 136건, 실패 0 / 오류 0 / skip 0** |
| 신규 snapshot 회귀 | 9건: 예약 0/1/28건의 설정 SQL 수, 전날 자정 넘김·마감 경계, 특정일 우선·브레이크, 추가/수정/삭제 전체 롤백, 요일 휴무, 특정일 soft-delete 후 복원·후속 변경 |
| 신규 별도 연결 동시성 | 12건: 요일 교체/특정일 설정/삭제 × 변경 또는 예약 선행 × 선행 트랜잭션 커밋/롤백 |
| 기존 PR #269 웨이팅 충돌 | 8건 통과. 서비스 동작 변경 없이 테스트 시간 의존 보완 |
| 기존 예약 회귀 | ReservationSettings 42, ReservationConcurrency 6, ReservationTimeValidation 2, ReservationAvailabilityService 2건 포함 |
| 나머지 기존 회귀 | BusinessHourService 5, SpecialBusinessDayService 13, 엔티티 20, 컨트롤러 17건 포함 |
| 비교 도구 | 기준 코드와 수정본 양쪽 `compileTestJava` 성공. 런타임 smoke·부하 실행은 2차에 남김 |
| 정적 확인 | `git diff --check` 통과 |

동시성 테스트는 각 actor의 `pg_backend_pid()`가 다름을 확인하고, `pg_stat_activity`의 Lock 대기와 `pg_blocking_pids()`로 첫 트랜잭션이 두 번째 연결을 막고 있음을 확인한 후 커밋/롤백 gate를 연다. 변경이 먼저 커밋되면 예약이 거절되고, 예약이 먼저 커밋되면 변경이 거절·롤백된다. 선행 작업이 롤백되면 대기한 반대 작업은 정상 진행하며 최종 예약 개수와 영업 구간도 검증한다.

### 실패 재현과 수정 이력

- 첫 컴파일에서 신규 테스트의 AssertJ wildcard import와 fixture `DATE` 이름이 충돌했다. 명시적 assertion import로 수정했다.
- 첫 관련 실행은 53건 중 기존 `BusinessHourWaitingConflictTest.closingTodayWithWaitingRollsBack` 1건이 실패했다. 실행 시각이 서울 00:20 무렵이어서 fixture의 “현재 ±1시간”이 전날 23시부터 이어진 영업을 포함했다. 당일을 휴무로 저장해도 전날 영업이 남으므로 기존 서비스 규칙상 거절 대상이 아니었다.
- `git archive`로 추출한 **기준 코드에서도 같은 웨이팅 실패를 재현**했다. 기존 테스트에 고정 정오 Clock을 주입하고 fixture도 그 Clock을 사용하게 수정했다. 시나리오나 assertion을 제거하거나 skip하지 않았다.
- 기준 코드에 신규 snapshot 회귀를 적용한 진단 실행은 17건 중 3건 실패: 위 시간 의존 1건과 SQL 상한 2건이다. 활성 예약 1/28건에서 **요일 SELECT가 각각 2/56회**여서 “1회” assertion이 실패했다. 수정본에서는 요일·특정일 SELECT가 각각 1회이고, 0건은 둘 다 0회였다. 이는 작은 fixture의 쿼리 회귀 근거이며 응답 시간/잠금 성능 개선율을 뜻하지 않는다.

### 실행 명령

```sh
DB_HOST=localhost DB_PORT=55432 DB_NAME=ct_improve_b \
DB_USERNAME=ct_improve_b DB_PASSWORD=ct_improve_b_local_only \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
./gradlew test --no-daemon --max-workers=2 \
  --tests 'com.catchtable.store.service.BusinessHourValidation*Test' \
  --tests 'com.catchtable.store.service.BusinessHourServiceTest' \
  --tests 'com.catchtable.store.service.SpecialBusinessDayServiceTest' \
  --tests 'com.catchtable.store.service.BusinessHourWaitingConflictTest' \
  --tests 'com.catchtable.store.entity.BusinessHourTest' \
  --tests 'com.catchtable.store.entity.SpecialBusinessDayTest' \
  --tests 'com.catchtable.store.controller.OwnerBusinessHourControllerTest' \
  --tests 'com.catchtable.store.controller.OwnerSpecialBusinessDayControllerTest' \
  --tests 'com.catchtable.reservation.service.ReservationConcurrencyTest' \
  --tests 'com.catchtable.reservation.service.ReservationSettingsTest' \
  --tests 'com.catchtable.reservation.service.ReservationTimeValidationTest' \
  --tests 'com.catchtable.reservation.service.ReservationAvailabilityServiceTest'

python3 scripts/performance/business-hour-validation/run.py compile
```

원시 증거는 이 워크트리의 `artifacts/business-hour-validation/{regression-1,regression-2,baseline-regression,regression-final,benchmark-compile}.log`에 있다. 최종 XML·HTML과 집계는 `build/business-hour-validation/final/`, 기존 실패 XML은 `build/business-hour-validation/regression-2/xml/`, 기준 코드 진단 XML은 `build/business-hour-validation/baseline/build/test-results/test/`에 보존했다. `build` 경로는 clean 전에 별도 보존해야 한다.

## 2차 성능 비교와 남은 범위

[실행 안내](../scripts/performance/business-hour-validation/README.md)의 `run.py`로 같은 fixture/Clock/JVM/DB 설정을 기준본과 수정본에 순서대로 적용한다. 기본 규모는 0/10/1000건, 워밍업 10, 표본 100, 반복 3회다. 요일 교체·특정일 설정/삭제의 단독/예약 경합을 분리하고 서비스 요청 시간·SQL 수·잠금 SQL 획득 시간·JDBC 기준 보유 시간·다른 연결의 샘플링된 DB 잠금 대기를 기록한다. p50/p95/p99와 오류율, 환경·소스 hash, 원시 CSV를 남긴다. 서비스 호출 기반이며 HTTP 응답 지연 측정은 아니다.

관리자 슬롯에서 계측 런타임 smoke와 본 비교를 수행해야 한다. 이번 단계에서는 응답 시간·SQL 총량·잠금 대기 시간의 before/after 부하 수치나 개선율을 주장하지 않는다. 다른 작업 통합, 전체 suite/CI, HTTP 부하, 배포·프런트 연동은 이번 검증에 포함되지 않는다. 커밋·push·PR·merge·외부 문서 변경을 하지 않았고, 워크트리와 전용 DB를 후속 검증용으로 남긴다.

## 후속 — 계측 runtime smoke (2026-11-11)

관리자의 별도 지시 `task_33f5043457a8`에 따라 본 성능 슬롯과 구분해 소규모 계측만 실행했다. before → after → summarize 순서였고 두 variant에 다음 값을 동일 적용했다.

```sh
B_PERF_SLOT_APPROVED=1 B_BENCH_SIZES=0,10 B_BENCH_WARMUP=0 \
B_BENCH_SAMPLES=1 B_BENCH_REPEATS=2 \
python3 scripts/performance/business-hour-validation/run.py run --variant before

B_PERF_SLOT_APPROVED=1 B_BENCH_SIZES=0,10 B_BENCH_WARMUP=0 \
B_BENCH_SAMPLES=1 B_BENCH_REPEATS=2 \
python3 scripts/performance/business-hour-validation/run.py run --variant after

python3 scripts/performance/business-hour-validation/run.py summarize
```

실행 전 기존 raw/환경/summary/benchmark 실행 로그는 없었다. 기존 컴파일 로그와 1차 회귀 증거는 보존했다. 새 결과는 실행 후 **`build/business-hour-benchmark-smoke-20261111/`**로 옮겨 기본 본 측정 경로와 분리했다. `before/after-raw.csv`, `before/after-environment.json`, `summary.json`, 실행 로그, 각 variant의 JUnit XML·HTML, 실행 당시 도구 소스, 독립 집계 점검 결과 `audit.json`, 파일 해시 `artifact-sha256.json`을 함께 보존했다.

| 계측 기능 점검 | before | after |
| --- | --- | --- |
| raw 요청 행 | 36 | 36 |
| 설정 변경 요청 / 경쟁 예약 요청 | 24 / 12 | 24 / 12 |
| 요청 오류 | 0 | 0 |
| JUnit | 1 test, 실패·오류·skip 0 | 1 test, 실패·오류·skip 0 |
| 경합 쌍의 서로 다른 backend PID | 12/12 (`267` / `268`) | 12/12 (`281` / `282`) |
| Lock 양성 sample이 관측된 예약 요청 | 12/12 | 12/12 |

36행은 `2반복 × 2규모 × 3작업 × (단독 writer 1 + 경합 writer/reservation 2)`다. 요일 교체·특정일 신규 설정·삭제 각각 단독/경합 경로에서 누락·중복 없이 결과가 생성됐고, 새 환경 JSON의 규모·표본·반복·Clock·JVM·Java·PostgreSQL 및 fixture/도구 SHA가 두 variant에서 일치함을 확인했다.

SQL 계측은 모든 raw 행에서 fixture의 예상값과 일치했다. 아래는 writer 한 요청의 전체 PreparedStatement execute 수 / 설정 SELECT 수이며, 단독·경합과 두 반복에서 동일했다. 검증 외 기존 설정 탐색·응답 조회도 포함한다.

| 활성 예약 | 작업 | before SQL / 설정 SELECT | after SQL / 설정 SELECT |
| --- | --- | --- | --- |
| 0 | 요일 교체 | 12 / 2 | 12 / 2 |
| 0 | 특정일 설정 | 4 / 1 | 4 / 1 |
| 0 | 특정일 삭제 | 4 / 1 | 4 / 1 |
| 10 | 요일 교체 | 52 / 42 | 14 / 4 |
| 10 | 특정일 설정 | 42 / 39 | 6 / 3 |
| 10 | 특정일 삭제 | 44 / 41 | 6 / 3 |

경쟁 예약은 특정일 설정 경로에서 8 / 3, 나머지에서 9 / 4였으며 양쪽 모두 일치했다. 모든 요청에서 잠금 획득·보유 계측값이 양수이고, 획득 시간+보유 시간이 서비스 요청 시간 안에 들어감을 확인했다.

`lock_acquire_ms`는 잠금 획득 대기뿐 아니라 SQL 실행·통신을 포함한다. `lock_hold_ms`는 잠금 SQL 반환부터 commit/rollback 반환까지의 JDBC 관측 근사치다. `observed_lock_wait_ms`는 약 2ms 간격으로 관측한 상태를 다음 관측까지 적용한 추정 구간 합이므로 SQL 획득 지연보다 일부 크게 나올 수 있으며 순수 DB 대기 시간과 동등하지 않다. 짧은 경합에서는 `wait_samples=0`도 가능하고 이를 실제 대기 0이나 기능 실패로 단정하지 않는다. 이번 smoke에서는 24개 경쟁 예약 모두 양성 sample을 관측했다.

`summary.json` 108개 그룹(반복별과 전체)을 raw에서 독립 재계산해 표본 수, 오류율 0, 대기 관측 요청 수, 6개 지표의 p50/p95/p99가 일치함을 확인했다. 그룹별 전체 표본이 2개이므로 p50은 최소값, p95/p99는 최대값이다. 이는 집계 기능 확인일 뿐 지연 분포나 응답 시간 개선율의 근거로 쓰지 않는다.

런타임 오류가 없어 도구/fixture/생산 코드를 수정하지 않았다. 실행 전후 `src/main` 전체 SHA-256 일치, fixture 매장 잔존 0건을 확인했다. 기존 136건 회귀는 이번에 재실행하지 않았다. `git diff --check`를 확인했고, 기본 1000건/100표본/3반복 부하·통합·게시 승인은 여전히 남아 있다.

## 후속 — 독점 본 성능 슬롯 측정 완료 (2026-11-11)

Task `task_6037cec02202`의 배정에 따라 before → after를 각각 한 번 실행하고 summarize했다. smoke/회귀 재실행이나 표본 축소 없이 **규모 0/10/1000, 워밍업 10, 측정 100, 반복 3**을 동일 적용했다. 기준은 `a44952e37993e5003c860714f126057cd311dba5`, 수정본은 1차 검증된 로컬 B 변경이다. baseline main 전체를 고정 커밋의 git archive와 대조했고, 수정본 main의 차이는 BusinessHourService와 SpecialBusinessDayRepository 두 파일뿐임을 실행 전에 확인했다.

### 명령, 시간, 로컬 전용 후속 지시

```sh
B_PERF_SLOT_APPROVED=1 B_BENCH_SIZES=0,10,1000 B_BENCH_WARMUP=10 \
B_BENCH_SAMPLES=100 B_BENCH_REPEATS=3 \
python3 scripts/performance/business-hour-validation/run.py run --variant before

B_PERF_SLOT_APPROVED=1 B_BENCH_SIZES=0,10,1000 B_BENCH_WARMUP=10 \
B_BENCH_SAMPLES=100 B_BENCH_REPEATS=3 \
python3 scripts/performance/business-hour-validation/run.py run --variant after

python3 scripts/performance/business-hour-validation/run.py summarize
```

호스트 기록은 `run-monitored.py`로 위 명령 주위에서 30초마다 수집했다. before는 01:13:54~03:15:53 KST, after는 03:16:51~03:17:55 KST에 실행됐고 둘 다 exit 0이다. Gradle 로그의 전체 소요 시간은 각각 **2시간 1분 58초 / 1분 4초**다. 이 전체 시간에는 준비·fixture·워밍업도 포함되므로 서비스 시간 비교에는 아래 raw 지표를 사용한다. 오래 걸린 직접 관측 근거는 기준본 1000건 변경 요청당 3,860~4,012 SQL과 수 초의 서비스 실행 시간이다. 대규모 변경 측정만 1,800회, 그 조건의 워밍업이 180회였다.

기준본 완료 직후 관리자 메시지 `msg_07c1c04af0ab`로 전 과정 로컬 전용 지시를 받았다. 이후 새 다운로드를 차단하도록 **run.py의 Gradle 명령에 `--offline` 한 항목만 추가**했다. 이는 의존성 해석/빌드 단계 옵션으로 측정 Java·fixture·JVM·DB·표본 수를 변경하지 않는다. 양쪽 benchmark/fixture/init 스크립트 SHA, Java/DB/JVM/Clock 설정 일치를 확인했다. 생산 코드는 전후 SHA가 모두 같고 수정하지 않았다. run.py 변경 전 SHA는 `0ed886acc5c55e8806a56e608404c6b229e869e9bb2d759daf16a8fb461927f7`, 변경 후는 `392c99d1f0883d186304dbb4fd9bd09b9bc199f0f360a4990b6cb36ecd952d0f`이며 원본과 이유는 산출물에 보존했다. 이번 본 측정에서 외부 서비스 조회/수정, Git fetch/pull/push, PR, 실제 PG/SOLAPI, 배포는 하지 않았다.

### 표본과 검증

- 각 variant raw **8,100행**: 설정 변경 5,400 + 경쟁 예약 2,700. 양쪽 합계 **16,200행**, 요청 오류 **0**, 오류율 **0%**. 워밍업 행은 raw에서 제외했다.
- 각 조건·actor의 전체 표본 300, 반복별 100. 각 variant JUnit 1건이 failures/errors/skipped 0으로 통과했다. 기존 136건 회귀를 다시 실행한 결과가 아니다.
- 경합의 **각 2,700쌍**에서 writer/예약의 backend PID가 다름을 확인했다. 측정 중 B DB의 JDBC 연결 6개가 모두 `ct_improve_b`에 연결돼 있음을 확인했다.
- **216개 집계 그룹**의 모든 표본 수·오류율·관측 수·6개 지표 p50/p95/p99를 raw에서 독립 재계산해 일치함을 확인했다. SQL/config 수 역시 모든 행에서 fixture 예상값과 일치했다.
- 모든 요청의 잠금 획득·보유 계측값이 양수이고 둘의 합이 request_ms 안에 들어갔다. 도구 런타임 오류나 재측정은 없었다.

### 전체 결과

시간 단위는 **ms**, 각 셀은 **p50/p95/p99**다. `single`은 변경 단독, `contended`는 다른 연결의 예약 생성과 경합이다. request는 **서비스 proxy 호출부터 커밋 반환까지**이며 HTTP 성능이 아니다. hold는 잠금 SQL 반환부터 commit/rollback 반환까지의 JDBC 보유 근사다.


| 예약 수 | 작업 | 경합 | 요청 before | 요청 after | 보유 before | 보유 after |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 요일 교체 | single | 2.978/12.984/15.753 | 3.005/6.848/7.872 | 2.755/11.424/14.638 | 2.778/6.240/7.209 |
| 0 | 요일 교체 | contended | 3.063/7.176/8.159 | 3.513/5.075/6.207 | 2.801/6.124/7.288 | 3.195/4.497/5.558 |
| 0 | 특정일 설정 | single | 1.582/3.250/3.853 | 1.591/2.077/2.396 | 1.317/2.630/3.046 | 1.332/1.710/1.990 |
| 0 | 특정일 설정 | contended | 1.507/3.601/4.201 | 1.773/2.334/2.852 | 1.250/2.869/3.429 | 1.455/1.889/2.108 |
| 0 | 특정일 삭제 | single | 1.780/5.309/6.579 | 1.698/1.969/2.632 | 1.453/4.552/5.477 | 1.405/1.609/2.283 |
| 0 | 특정일 삭제 | contended | 1.942/3.165/5.743 | 1.745/2.050/2.454 | 1.552/2.682/3.787 | 1.429/1.661/1.980 |
| 10 | 요일 교체 | single | 10.995/17.205/18.639 | 3.759/4.683/5.004 | 10.738/16.661/18.100 | 3.463/4.274/4.593 |
| 10 | 요일 교체 | contended | 10.617/16.889/24.165 | 4.028/4.528/4.862 | 10.367/16.183/23.234 | 3.716/4.173/4.428 |
| 10 | 특정일 설정 | single | 9.694/15.106/19.491 | 1.936/2.617/3.112 | 9.319/14.154/18.872 | 1.701/2.313/2.760 |
| 10 | 특정일 설정 | contended | 9.473/13.053/17.979 | 2.096/2.812/3.103 | 9.148/12.594/17.477 | 1.822/2.423/2.710 |
| 10 | 특정일 삭제 | single | 8.781/13.284/17.099 | 1.865/2.447/2.880 | 8.565/12.678/16.763 | 1.646/2.161/2.566 |
| 10 | 특정일 삭제 | contended | 8.973/12.680/13.812 | 2.392/3.171/3.957 | 8.746/12.222/13.293 | 2.083/2.768/3.017 |
| 1000 | 요일 교체 | single | 3559.395/6389.687/6641.053 | 9.369/11.164/13.398 | 3558.885/6389.195/6640.509 | 9.050/10.869/13.096 |
| 1000 | 요일 교체 | contended | 3204.355/3710.016/5621.588 | 9.037/15.396/17.185 | 3203.973/3709.423/5620.594 | 8.725/15.031/16.788 |
| 1000 | 특정일 설정 | single | 3207.503/6623.118/6991.051 | 6.758/9.006/12.227 | 3207.023/6622.326/6990.336 | 6.417/8.640/11.764 |
| 1000 | 특정일 설정 | contended | 3082.819/3474.817/4300.538 | 6.778/12.087/14.152 | 3082.481/3474.498/4300.138 | 6.457/11.558/13.630 |
| 1000 | 특정일 삭제 | single | 3222.803/6532.281/7129.341 | 6.413/7.843/9.813 | 3222.399/6531.649/7128.940 | 6.108/7.547/9.432 |
| 1000 | 특정일 삭제 | contended | 3157.490/3714.449/4457.069 | 7.015/11.923/12.906 | 3157.165/3714.070/4456.605 | 6.691/11.513/12.496 |

### 별도 연결 예약 생성 (경합)

| 예약 수 | 변경 | 예약 요청 before | 예약 요청 after | 획득 SQL before | 획득 SQL after | 관측 대기 before | 관측 대기 after | 양성 관측 before/after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | 요일 교체 | 5.014/12.113/13.953 | 5.887/8.706/10.832 | 2.527/5.438/6.621 | 2.884/4.009/5.112 | 2.510/5.671/7.602 | 2.558/4.807/5.327 | 298/300 · 300/300 |
| 0 | 특정일 설정 | 3.422/7.518/9.266 | 4.016/5.026/6.158 | 0.999/2.241/2.654 | 1.140/1.456/1.689 | 0.000/2.818/3.048 | 0.000/0.000/2.669 | 89/300 · 10/300 |
| 0 | 특정일 삭제 | 4.656/7.904/13.687 | 4.266/4.895/5.660 | 1.198/2.129/2.779 | 1.100/1.272/1.512 | 0.000/2.856/3.262 | 0.000/0.000/0.000 | 73/300 · 1/300 |
| 10 | 요일 교체 | 12.548/20.380/31.544 | 6.339/7.182/7.632 | 10.131/15.608/22.032 | 3.395/3.836/4.013 | 9.842/15.975/23.876 | 2.687/2.746/2.819 | 300/300 · 300/300 |
| 10 | 특정일 설정 | 11.544/15.611/20.152 | 3.889/5.171/5.821 | 8.843/12.179/17.107 | 1.540/2.074/2.399 | 7.602/11.563/16.824 | 0.000/2.688/2.732 | 300/300 · 32/300 |
| 10 | 특정일 삭제 | 10.807/15.526/17.006 | 4.842/6.136/6.877 | 8.528/11.590/12.870 | 1.760/2.325/2.589 | 7.439/11.148/12.825 | 0.000/2.747/2.765 | 300/300 · 72/300 |
| 1000 | 요일 교체 | 3207.080/3713.092/5624.686 | 12.011/19.112/21.067 | 3203.602/3708.971/5619.895 | 8.454/14.718/16.456 | 3202.972/3709.076/5618.858 | 7.772/13.712/15.219 | 300/300 · 300/300 |
| 1000 | 특정일 설정 | 3085.220/3477.336/4302.834 | 9.445/15.512/17.510 | 3082.197/3474.303/4299.803 | 6.154/11.019/13.324 | 3080.335/3472.761/4299.798 | 5.117/10.829/11.217 | 300/300 · 300/300 |
| 1000 | 특정일 삭제 | 3160.021/3716.944/4459.891 | 10.167/16.008/17.643 | 3156.793/3713.744/4456.197 | 6.426/11.187/12.234 | 3155.926/3712.665/4454.983 | 5.271/10.996/11.139 | 300/300 · 300/300 |

### Writer SQL 수 / 설정 SELECT 수

| 예약 수 | 변경 | before | after |
| --- | --- | --- | --- |
| 0 | 요일 교체 | 12 / 2 | 12 / 2 |
| 0 | 특정일 설정 | 4 / 1 | 4 / 1 |
| 0 | 특정일 삭제 | 4 / 1 | 4 / 1 |
| 10 | 요일 교체 | 52 / 42 | 14 / 4 |
| 10 | 특정일 설정 | 42 / 39 | 6 / 3 |
| 10 | 특정일 삭제 | 44 / 41 | 6 / 3 |
| 1000 | 요일 교체 | 4012 / 4002 | 14 / 4 |
| 1000 | 특정일 설정 | 3860 / 3857 | 6 / 3 |
| 1000 | 특정일 삭제 | 4004 / 4001 | 6 / 3 |


### 반복별 차이와 해석

1000건 writer의 요청 p95는 아래와 같다. 전체 p50/p95/p99 및 나머지 지표의 반복별 값은 `comparison.md`와 `summary-flat.csv`에 모두 보존했다.

| 변경 | 조건 | before p95 (반복 1 / 2 / 3) | after p95 (반복 1 / 2 / 3) |
| --- | --- | --- | --- |
| 요일 교체 | single | 4127.561 / 6490.256 / 6503.818 | 10.315 / 12.511 / 10.676 |
| 요일 교체 | contended | 3490.689 / 5412.070 / 3611.773 | 10.128 / 9.710 / 16.315 |
| 특정일 설정 | single | 6097.567 / 6441.188 / 6884.385 | 7.985 / 7.811 / 11.435 |
| 특정일 설정 | contended | 3279.372 / 3470.783 / 3982.797 | 7.493 / 10.663 / 13.069 |
| 특정일 삭제 | single | 5967.719 / 6497.413 / 6927.649 | 7.347 / 7.728 / 8.647 |
| 특정일 삭제 | contended | 3701.586 / 3229.073 / 4038.321 | 10.134 / 11.875 / 12.781 |

- **0건:** SQL 수가 양쪽 동일하고 중앙값 변화 방향도 섞였다. snapshot 최적화의 효과가 없는 조건이며 여기의 작은 시간 차이를 코드 개선 효과로 단정하지 않는다.
- **10건:** 세 작업 모두 단독/경합의 writer 요청·보유 시간과 경쟁 예약의 획득 SQL 시간이 이번 측정에서 감소했다. 전체 호출 SQL은 요일 52→14, 설정 42→6, 삭제 44→6이었다.
- **1000건:** 단독 writer 요청 p95는 요일 6389.687→11.164, 설정 6623.118→9.006, 삭제 6532.281→7.843이었다. 경합 writer 보유 p95도 각각 3709.423→15.031, 3474.498→11.558, 3714.070→11.513으로 줄었다. 세 반복 각각에서도 방향이 같았지만 정확한 배율을 일반화하지 않는다.
- 경쟁 예약의 `lock_acquire_ms`는 **잠금 대기+SQL 실행+통신 시간**이다. 순수 DB 잠금 대기로 부르지 않는다. `observed_lock_wait_ms`는 약 2ms 간격 관측으로 추정한 구간 합이며 경계 오차로 획득 SQL 시간보다 일부 크게 나올 수 있다.
- 양성 Lock sample을 관측한 예약은 before **2,260/2,700**, after **1,615/2,700**이었다. 1000건은 양쪽 모두 900/900에서 관측했다. `wait_samples=0`인 440/1,085건은 짧은 대기 미관측 가능성이 있어 실제 대기 0 또는 검증 실패로 해석하지 않는다.

### 환경 한계와 종료 상태

같은 Apple M5 Pro 호스트(논리 CPU 18, RAM 48GiB)에서 JVM CPU 수 2·heap 512MiB·Hikari 6개, PostgreSQL 16.15 전용 B DB, 고정 Clock/동일 fixture를 사용했다. before의 1분 load average는 min/median/max **2.883/4.574/12.097**(244회 관측), after는 **3.341/3.525/3.686**(3회 관측)였다. Chrome·WindowServer·Zoom·Codex 등 사용자/검토 앱의 부하가 있었고 변경하거나 종료하지 않았다. before→after 순서가 고정되고 실행 길이·호스트 부하가 달라 JIT/GC·캐시·DB 물리 상태 및 시간 경과 편향이 남는다. 관측 연결도 경합 조건에 부하를 더한다. 쿼리 수의 구조적 감소와 이 fixture에서의 시간 감소는 확인했지만 운영 환경의 정확한 개선 배율·다중 요청 처리량·HTTP 지연을 입증한 결과는 아니다.

03:18:42 KST에 Java 프로세스 0, 앱 18082/fake HTTP 19082 리스너 0, B DB JDBC 세션 0, benchmark fixture 매장 잔존 0을 확인했다. 두 Gradle/측정 드라이버는 exit 0으로 종료했다. 전용 PostgreSQL 컨테이너 `ct-improve-b-20261111`은 데이터를 보존한 채 실행 상태로 남겼다. 다른 DB·사용자 앱·워크트리를 건드리지 않았다.

본 증거 정본은 **`build/business-hour-benchmark-main-20261111/`**에 복사 보존했다. raw/환경/summary/flat CSV/상세 비교, 두 JUnit XML·HTML, 명령·시각·호스트 JSONL, 소스 지문, offline 변경 전 스크립트 및 변경 이유, `audit.json`, `resource-end-state.json`이 있다. 기존 작은 smoke는 `build/business-hour-benchmark-smoke-20261111/`에 그대로 남겼다. `build` 경로이므로 clean 전에 이 폴더를 보존해야 한다.

본 성능 비교까지 완료했으며 남은 범위는 다른 담당과의 통합 검증·사용자 의사 결정 감사 및 별도 승인 후 게시다. 커밋·push·PR·merge·배포는 하지 않았다.
