# 예약 가능 시간의 가용 수량 일괄 조회

- 작업일: 2026-11-11
- 기준 HEAD: `a44952e37993e5003c860714f126057cd311dba5`
- 브랜치: `perf/availability-batch`
- 명세: [예약 가능 시간·잔여 테이블 조회](https://app.notion.com/p/3d548d76fa32817389b8dee3a96d5350). 이번에 읽은 페이지의 마지막 편집일은 2026-11-01이며, 현재 소스와 대조했다.
- 범위: 공통 계약의 1차 구현·관련 회귀·비교 도구 준비. 커밋·push·PR·merge·배포·외부 문서 변경·실제 PG 호출은 하지 않았다.

## 변경과 보존 경계

기존 `getAvailability`는 후보 슬롯마다 `StoreTableRepository.countAvailable`을 실행했다. 영업 구간에서 후보를 생성하는 반복문, 전날 영업·이용 종료·슬롯 올림·예약 마감·Clock/Asia/Seoul 규칙은 그대로 두고, 후보 시각을 정렬·중복 제거한 후 한 SQL에 전달한다.

`StoreTableAvailabilityRepository`의 Spring Data fragment는 바인딩된 `(순번, 시작, 종료)` VALUES 집합과 활성·수용 가능 테이블을 LEFT JOIN하고 슬롯별 수량을 반환한다. 테이블이 없거나 모두 점유된 슬롯도 0을 반환하고 빈 슬롯 입력은 SQL을 실행하지 않는다. 기존 native SQL/JPA 경계를 재사용해 서비스의 기존 주입 계약을 유지했다. 사용자 입력을 SQL 문자열에 연결하지 않으며, 동적으로 구성하는 부분은 내부 순번과 파라미터 이름뿐이다.

단건 쿼리의 예약 상태 제외, 선점 `hold_expires_at > now`, `approval_payment_id`·`approval_quarantined` 보호와 `tstzrange(..., '[)')` 조건을 그대로 적용했다. 두 수량 쿼리의 의미가 갈라지지 않도록 같은 fixture로 직접 비교하는 테스트를 추가했다. batch SQL 내부의 anti-join 처리 비용은 줄었다고 가정하지 않는다.

- 단건 수량 API·실제 예약 생성의 `findAvailableForUpdate`, 행 잠금, 시간 중복 제약, 결제 보호 상태 변경 코드는 수정하지 않았다.
- BusinessHourService·SpecialBusinessDayService 및 공개 계약은 수정하지 않았다.
- 결과는 조회 순간값이다. batch는 한 statement snapshot에서 모든 슬롯을 계산하며 조회 이후 생성 성공을 보장하지 않는다. 동시 쓰기 중 여러 statement 사이의 상태 혼합을 재현하려는 변경은 아니다.
- 기존·신규 마이그레이션 및 공통 application.yml/build.gradle 변경 없음. V38 인덱스는 추가하지 않았다.

## 이번 검증

Java 21.0.12.1, PostgreSQL 16.15, 신규 전용 컨테이너 `ct-improve-a-20261111`, `127.0.0.1:55431`, DB `ct_improve_a`를 사용했다. JPA ddl-auto validate와 Flyway를 유지했다. 실제 비밀값을 사용하지 않았고 Toss는 loopback 19081 또는 기존 테스트 mock, Alimtalk는 비활성이다.

| 실행 | 실제 결과 |
| --- | --- |
| 변경 전 기존 ReservationAvailabilityServiceTest | 2건 통과, 실패/오류/skip 0 |
| 최초 batch 및 기존 가용성 회귀 | 20건 통과, 실패/오류/skip 0 |
| SQL 호출 수 테스트 추가 후 관련 7클래스 | 132건 중 새 테스트 단언 1건 실패. 09:00~22:00의 30분 슬롯 수를 25로 잘못 예상함 |
| 단언을 기존 규칙의 27로 정정한 뒤 관련 7클래스 재실행 | **132건 통과, 실패/오류/skip 0** |
| 기준/수정본 비교 도구 smoke | 버전별 1건씩 통과, 전체 응답 동일 |

최종 132건은 가용성 신규 19·기존 2, 시간 검증 2, 별도 연결 예약 생성 경합 6, 예약 설정 42, 예약 결제 46, 결제 재확인 15다. 앞선 실행 건수와 합산하지 않는다.

신규 회귀는 만료 1μs 전·정각·후, 승인 진행/결과 불명/완료 결제의 시작 보호 및 격리, 예약의 전체 상태, 맞닿는 시간 끝점, 입력 순서·중복, 최소/최대 인원, 비활성/다른 매장/없는 테이블, 특별 영업일·브레이크·마감 경계, 특정일 휴무와 전날 밤 영업, 중복 영업 구간, 조회일 자정 이후 종료를 포함한다. 실제 DB에서 같은 starts/now/fixture로 기존 단건 SQL과 batch 결과를 비교한다. 27개 후보 슬롯의 수량 SQL 1회와 빈 입력 0회도 SQL capture로 확인했다. 기존 회귀가 생성 경합·선점 재사용·확정 실패·불명 보호를 다시 검증했다.

```bash
DB_HOST=127.0.0.1 DB_PORT=55431 DB_NAME=ct_improve_a DB_USERNAME=catchtable \
DB_PASSWORD="$AVAILABILITY_DB_PASSWORD" \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:19081 ALIMTALK_ENABLED=false \
./gradlew test --tests '*ReservationAvailability*Test' \
  --tests '*ReservationTimeValidationTest' --tests '*ReservationConcurrencyTest' \
  --tests '*ReservationSettingsTest' --tests '*ReservationPaymentServiceTest' \
  --tests '*PaymentReconcileServiceTest' --no-daemon --max-workers=2 --console=plain
```

원본 로그·XML은 이 워크트리 `build/availability-evidence/`의 `baseline.log`, `baseline-xml/`, `batch-regression.log`, `batch-xml/`, `related-regression.log`, `failed-related-xml/`, `related-regression-final.log`, `final-related-xml/`에 있다. 최초 실패도 보존했다. `git diff --check`와 새 파일의 공백 검사를 통과했다.

## 비교 도구와 1차 smoke 범위

[도구 실행법과 측정 정의](../tools/availability-benchmark/README.md)에 기준/수정본의 동일 fixture·Clock·요청·JVM 설정, SQL 집계 범위, 워밍업·표본·반복, p50/p95/p99·오류율, EXPLAIN과 자원 기록을 정리했다. Python 3.9의 tarfile 호환 오류로 첫 smoke가 실행 전 실패했고, 로컬 git archive 추출 방식만 수정한 다음 정상 실행했다.

실행: `AVAILABILITY_DB_PASSWORD=... python3 tools/availability-benchmark/run.py --smoke`.

원시 결과: `build/availability-benchmark/20261111-002339/`.

매장 1개·테이블 4개·예약 48개·슬롯 53개, 워밍업 0·버전별 요청 1회에서 응답 전체가 같았다. 성공한 MockMvc 요청 스레드의 Hibernate SQL 준비 호출은 전체 **58→6**, 수량 **53→1**이었다. setup·Flyway·트랜잭션 제어·background·네트워크는 제외한 단위다. 정확한 SQL은 각 버전 `request.sql`, 조건은 `metadata.json`, 호출 수는 `summary.json`에 있다. 이 1회 smoke의 시간값으로 개선율이나 백분위를 주장하지 않는다.

## 남은 검증

관리자의 2차 순차 배정 후 3개 매장·120개 테이블·10,080개 예약·53개 슬롯을 기본으로, 버전당 워밍업 50·표본 200·3회 반복의 응답 시간 분포·오류율·실제 count SQL의 EXPLAIN (ANALYZE, BUFFERS)을 비교해야 한다. EXPLAIN 실행 경로는 1차에서 실행하지 않았다. 컨테이너 자원 시점값·DB 누적 카운터는 피크나 대기 시간 분포가 아니며, 같은 호스트의 다른 작업 영향이 남는다. 필요성이 확인된 경우에만 V38 인덱스를 별도 판단한다.

전체 build·작업 B/C/D 통합 회귀·CI·프런트/실제 네트워크·실제 Toss·배포 검증은 하지 않았다. 전용 컨테이너와 작업트리를 보존했으며 앱/HTTP 서버는 띄우지 않았다. 이번 결과는 1차 완료이며 개선 전체 완료나 게시 승인을 뜻하지 않는다.

## 2026-11-11 후속 — Standards P2 반복 실행 누락 수정

조율 보고서 `reports/review-ab-standards.md`의 P2는 당시 **정적 분석·미재현** 지적이었다. 기존 `run.py`가 같은 before/after 빌드 디렉터리에서 출력 환경변수만 바꾸므로 두 번째 `test`가 UP-TO-DATE로 생략될 수 있었다. 위 1회 smoke 성공 기록은 보존하며, 그것으로 반복 실행까지 검증한 것은 아니었다.

이번에는 작은 fixture 반복을 명시적으로 허용받아 `--smoke --repetitions 2` 옵션부터 추가하고, 원래 init script를 유지한 상태에서 재현했다. 한 실행의 `1-before`, `1-after`는 실제 실행됐지만 `2-after/gradle.log`에 `> Task :test UP-TO-DATE`가 기록됐다. `2-after`의 XML은 `1-after`와 timestamp·SHA-256이 같았고 새 samples.json·response.json은 없었다. runner는 samples.json을 읽는 지점에서 FileNotFoundError, exit 1로 중단했고 `2-before`에는 도달하지 않았다.

최소 수정은 다음과 같다.

- `benchmark.init.gradle`: 전용 benchmark의 `test`에 `outputs.upToDateWhen { false }`, `outputs.doNotCacheIf(...) { true }`를 적용했다. 각 반복은 실제 실행하며 컴파일 등 선행 task 재사용은 유지한다.
- `run.py`: 명시한 `--repetitions`를 smoke에서도 반영한다. 생략 시 smoke 1회·measure 3회는 유지하고, smoke의 작은 fixture·워밍업 0·표본 1도 그대로다.
- 도구 README: 반복 smoke 명령, 두 모드의 기본값·재실행 정책을 맞췄다. 애플리케이션·회귀 테스트·마이그레이션·benchmark Java 소스는 변경하지 않았다.

수정 후 **하나의 명령**에서 같은 `before/`, `after/` 빌드 디렉터리를 재사용해 before→after→after→before 순으로 4회 실행했다. 빌드 캐시도 켜서 검증했다.

```bash
# 수정 전 재현: smoke 반복 옵션만 추가, 원래 init script 유지
AVAILABILITY_DB_PASSWORD="$AVAILABILITY_DB_PASSWORD" \
python3 tools/availability-benchmark/run.py --smoke --repetitions 2

# 수정 후 검증: 동일 fixture·동일 반복, build cache 활성화
GRADLE_OPTS=-Dorg.gradle.caching=true \
AVAILABILITY_DB_PASSWORD="$AVAILABILITY_DB_PASSWORD" \
python3 tools/availability-benchmark/run.py --smoke --repetitions 2

# 원시 로그/XML·파일 시각·응답·소스 해시 재확인
python3 build/availability-evidence/p2-rerun/verify.py
```

- 결과: **실제 test 4회, 각 1건 통과, 실패/오류/skip 0**, runner exit 0. 네 로그 모두 `> Task :test`이며 해당 task의 UP-TO-DATE/FROM-CACHE가 없다.
- 각 실행의 새 XML timestamp와 SHA-256을 확인했다. 같은 버전의 2회차는 XML·samples.json·response.json mtime이 1회차보다 새롭고, JSON 파일은 해당 테스트 시작 이후 생성됐다.
- 네 응답 JSON이 모두 동일하다. 각 실행의 metadata는 매장 1·테이블 4·예약 48·슬롯 53·워밍업 0·표본 1이다.
- 후속 시작/종료의 전체 `src` 파일별 SHA-256이 같다. 기존 132건 회귀는 이번에 재실행하지 않았으며 위 1차 결과로만 남긴다.

원본 증거(모두 현재 작업트리 상대 경로):

| 구분 | 경로 |
| --- | --- |
| 수정 전 재현 | `build/availability-benchmark/20261111-004047/` |
| 수정 후 2회 반복·총 4회 실행 | `build/availability-benchmark/20261111-004141/` |
| 요약 stdout·재현/검증 JSON·원래 도구·검증 스크립트·명령·소스 해시 | `build/availability-evidence/p2-rerun/` |

각 run의 `<반복>-<버전>/gradle.log`, `test-results/TEST-*.xml`, `samples.json`, `response.json`, `metadata.json`이 근거다. `p2-rerun/reproduction-verification.json`은 최초 실패를, `fixed-verification.json`은 네 실행의 timestamp·파일 mtime·해시·동일 응답 검사를 보존한다. Python 구문·CLI help 및 `git diff --check`도 확인했다.

이 후속은 도구 실행의 정확성 검증이다. 응답 시간 개선율, 대규모 fixture, EXPLAIN ANALYZE, V38, 통합·게시 작업은 수행하지 않았다. 기존 2차 성능 측정 필요성은 그대로 남는다.

## 2026-11-11 2차 — 배정된 순차 성능 측정과 실행 계획

관리자가 큰 검증 종료 후 A 전용 측정 슬롯을 배정했다. 기존 smoke/실패 자료를 보존하고 아래 명령을 **한 번** 실행했다. 도구 런타임 오류 없이 exit 0이어서 도구·앱 코드는 추가 수정하지 않았다. 전체 회귀도 다시 실행하지 않았다.

```bash
AVAILABILITY_DB_PASSWORD="$AVAILABILITY_DB_PASSWORD" \
python3 tools/availability-benchmark/run.py --measure --repetitions 3 --warmup 50 --samples 200
```

실제로는 위 명령을 `build/availability-evidence/phase2/run-measure.py`가 실행하면서 stdout과 5초 간격 호스트 load/프로세스 CPU 시점값을 함께 저장했다. 실행 시간은 2026-11-11 00:49:08~00:50:42 KST다. 사용자 앱에는 읽기 전용 관찰만 했다.

### 조건·동등성·실행 확인

- 기준: 기존과 같은 `a44952e37993e5003c860714f126057cd311dba5` archive와 현재 수정본. Java 21.0.12.1, JVM heap 512 MiB, PostgreSQL 16.15, 동일 전용 DB/포트, 매 실행 새 `availability_benchmark` 스키마와 같은 fixture 생성 코드·인자를 사용했다.
- 매장 3개, 매장당 테이블 40개(총 120개), 예약 이력 14일×테이블당 6건/일(총 10,080건), 조회 대상 슬롯 53개. 첫 매장/2명/2030-01-21 요청, Clock은 2030-01-20 09:00 Asia/Seoul이다. 활성·최소 인원 조건을 통과한 대상 테이블은 32개다.
- 여섯 실행의 metadata가 이 규모·조건과 일치한다. 마지막 DB 직접 조회도 매장 3·테이블 120·예약 10,080·요일 영업 21건, 예약 상태 CONFIRMED/CANCELLED/EXPIRED/PENDING_PAYMENT 각 2,520건을 확인했다. 동등성의 근거는 동일한 결정적 fixture 생성 및 파라미터·매회 스키마 재생성이다. 실행별 전체 DB 행 해시는 별도로 수집하지 않았다.
- 순서는 `1-before → 1-after → 2-after → 2-before → 3-before → 3-after`다. 각 실행은 50회 워밍업 뒤 200표본이며, 측정 표본은 총 1,200건(버전당 600건), 워밍업은 별도 총 300회다.
- 여섯 Gradle test가 모두 실제 실행되었고 각각 1건 통과, 실패·오류·skip 0이다. XML timestamp/hash가 새로 생성됐다. 측정 표본별 HTTP status는 전부 200이며 **오류 0/1,200**이다.
- 각 실행 내부의 성공 응답과 before/after 전체 JSON 동등성을 검사했다. 보존된 여섯 response.json의 SHA-256도 모두 `0f683221f49b9ed3231761528674edefed60f831a6a4d64d8659bd23007a6f0b`로 같다. 슬롯 순서·시각·수량·가용 여부가 일치한다.
- 시작/종료의 전체 src 및 성능 도구 소스 해시가 같았다. 기존 132건 회귀 결과는 이전 검증으로 유지한다.

### 요청 시간과 SQL 준비 횟수

시간은 단일 스레드의 **MockMvc 요청 전체 경과 시간**이다. MVC 입력 처리·서비스 트랜잭션·JDBC·직렬화를 포함하고 실제 HTTP 소켓·네트워크·브라우저·동시 사용자 부하는 포함하지 않는다. 백분위는 각 실행의 200개 표본에서 nearest-rank로 재계산해 원본 summary와 대조했다.

| 반복 | 실행 순서 | p50 ms (before → after) | p95 ms (before → after) | p99 ms (before → after) | 표본 오류 (before → after) |
| --- | --- | --- | --- | --- | --- |
| 1 | before → after | 21.447 → 9.422 | 26.092 → 15.989 | 37.331 → 18.881 | 0/200 → 0/200 |
| 2 | after → before | 20.717 → 9.145 | 30.879 → 12.344 | 35.772 → 15.829 | 0/200 → 0/200 |
| 3 | before → after | 19.919 → 9.276 | 22.433 → 11.709 | 28.104 → 13.284 | 0/200 → 0/200 |

세 쌍의 p50 감소는 이 조건에서 53.43~56.07%였다. p95는 38.72~60.02%, p99는 49.42~55.75%로 변동했다. 이 범위를 배포 환경의 보장값이나 일반 성능 개선율로 확대하지 않는다.

모든 측정 표본에서 **요청당 총 SQL 준비 호출 58→6, 수량 SQL 준비 호출 53→1**이었다. 단위는 요청 스레드의 Hibernate StatementInspector 호출이며 fixture·Flyway·TX 제어·background·EXPLAIN은 제외한다. 이 수치는 DB 내부 인덱스 탐색·scan 반복 횟수가 아니다.

### EXPLAIN ANALYZE BUFFERS — 슬롯 53개 합계와 batch 1개

각 실행의 표본 수집 이후, 실제 캡처된 수량 SQL과 동일 조건 바인딩으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행했다. before는 슬롯 53개 모두, after는 batch 1개이며 3회 반복 전체에서 plan 162개를 확보했다. 아래 시간은 표본의 요청 지연 분포가 아니라 **별도 EXPLAIN 실행의 Execution/Planning Time**이다.

| 반복 | DB execution ms (before 53개 합계 → after 1개) | planning ms (before 합계 → after) | execution shared hit (before 합계 → after) |
| --- | --- | --- | --- |
| 1 | 7.928 → 4.465 | 4.583 → 0.152 | 2,604 → 3,959 |
| 2 | 7.634 → 5.314 | 3.959 → 0.173 | 2,604 → 3,959 |
| 3 | 7.374 → 4.671 | 3.559 → 0.167 | 2,604 → 3,959 |

버퍼는 각 statement **루트 노드의 실행 수치만** 합산했다. 자식 노드의 포함된 수치를 중복 합산하지 않았다. planning shared hit는 별도로 583→1이며 위 execution hit에 포함하지 않는다. 모든 plan의 shared read, shared dirtied/written, temp read/written은 0이었다. 캐시에 올라온 데이터 조건이며 디스크 cold-cache 결과가 아니다.

세 반복의 물리 plan 구조와 rows/loops는 같았다.

| 관찰 대상 | before: 슬롯별 SQL 53개 합계 | after: batch SQL 1개 |
| --- | --- | --- |
| 최상위 출력 | Aggregate 1행×53 SQL = 53행 | GroupAggregate 53행×1 loop |
| 테이블 접근 | store_table Seq Scan 53회. 매회 120행 검사, 32행 통과/88행 제외. 총 6,360행 검사 | Seq Scan 1회, 120행 검사·32행 통과. Materialize의 32행을 53회 재사용 |
| 예약 접근 | 기존 `ex_reservation_table_period` GiST Bitmap Index Scan + Bitmap Heap Scan 각 53회 | 같은 GiST의 table_id+시간 조건 Index Scan, **1,696 loops = 53×32** |
| 예약/조인 처리 | 시간대가 겹치는 인덱스 출력 합계 2,340행, heap filter 통과 1,170행·제외 1,170행. 매 SQL은 Hash Right Anti Join | Nested Loop Left Join에서 1,696개 슬롯/테이블 조합 중 점유 312개 제외, 1,384개 통과 후 슬롯별 count |
| 정렬 | 없음 | quicksort, 메모리 103 KiB, 1,384행 → 집계 53행 |

before의 예약 행 합계는 겹치는 여러 슬롯·매장을 거치며 반복 관찰한 수치이고 고유 예약 수가 아니다. after의 예약 Index Scan은 `Actual Rows=0, Actual Loops=1696`으로 표시되는데, 반복당 평균을 정수로 표시한 결과이므로 “예약을 한 행도 읽지 않음”으로 해석할 수 없다. 정확히 확인되는 점유 조합 수는 상위 Join Filter가 제거한 312개다. `rows`는 출력 행 수이며 반복 노드의 시간·행 수는 회당 평균이라는 [PostgreSQL 16 EXPLAIN 설명](https://www.postgresql.org/docs/16/using-explain.html#USING-EXPLAIN-ANALYZE)을 기준으로 해석했다.

따라서 batch는 SQL 준비·전송과 테이블 반복 스캔을 줄였으나, 예약 인덱스는 더 많은 좁은 탐색을 한다. 실행 shared hit는 **52.04% 증가**했다. “1 SQL이므로 DB scan도 1회” 또는 “모든 DB 작업량이 감소했다”는 결론은 아니다. EXPLAIN 자체의 계측 overhead·별도 planning·직렬화/네트워크 제외 때문에 이 시간을 요청 지연의 정확한 DB 비중으로 빼서 계산하지도 않는다.

이번 범위에서는 기존 GiST가 두 쿼리에 사용되고, batch의 execution time과 요청 분포가 세 반복 모두 낮으며 추가 디스크/임시 파일 사용도 없었다. **추가 인덱스의 필요성을 입증하는 근거가 없어 V38은 추가하지 않았다.** 1,696번의 내부 탐색이 테이블·슬롯 수에 비례해 늘어나는 점은 향후 더 큰 규모의 재평가 대상으로 남긴다.

### 호스트·자원 관측과 해석 한계

호스트는 논리 CPU 18개·메모리 48 GiB다. 5초 간격 19개 표본의 1분 load average는 4.07~9.59였다. 측정 준비·컴파일·test JVM 부하도 포함된다. 같은 표본의 사용자 앱 단일 프로세스 CPU 최대 관측치는 WindowServer 72.7%, Slack Helper 134.4%, Chrome 43.4%, Zoom 25.5%, Orca 계열 20.1%였다. CPU 값은 프로세스별 시점값이며 앱 전체 합계나 구간의 실제 최대값이 아니다. 사용자 앱을 종료·조절하지 않았다.

| 실행 | 1분 load: 시작 → 종료 |
| --- | --- |
| 1-before | 4.07 → 8.03 |
| 1-after | 8.03 → 7.83 |
| 2-after | 9.21 → 9.38 |
| 2-before | 9.38 → 7.90 |
| 3-before | 7.90 → 7.53 |
| 3-after | 7.53 → 7.70 |

Docker 시작/종료 시점의 PostgreSQL 컨테이너 메모리는 67.57~73.41 MiB였다. CPU는 대부분 요청 구간 밖에서 취한 값이므로 요청 처리 피크나 CPU 병목 여부의 근거로 쓰지 않는다. DB의 종료 시점 잠금 대기 0·누적 deadlocks 0을 여섯 실행에서 확인했지만, 대기 시간 분포나 동시성 부하 측정은 아니다.

순서를 교대하고 동일 워밍업을 적용했어도 사용자 앱·스케줄링·JIT·OS/DB 캐시 영향을 분리하지 못한다. before p95가 26.09→30.88→22.43ms, after p95가 15.99→12.34→11.71ms로 달라졌다. 반복당 200표본의 꼬리 백분위에는 소수 관측치의 영향도 크다. 실제 앱 네트워크·동시 사용자·처리량·cold-cache·다른 규모·운영 SLO의 증거로 사용하지 않는다.

### 원시 증거와 종료 상태

- 본 실행: `build/availability-benchmark/20261111-004908/`의 run.json·summary.json, before/after 입력 snapshot과 해시, 각 실행의 gradle.log·XML·samples.json·response.json·request.sql·plans.json·metadata.json·resources-before/after.json·command.json.
- 추가 분석/관측: `build/availability-evidence/phase2/`의 command.json·execution.json·measure.log·input-hashes.json·host.txt·host-samples.jsonl·analysis.json·plan-nodes.json·analyze.py·run-measure.py·final-db-state.txt·remaining-processes.txt·container-state.txt.
- `analyze.py`가 1,200개의 status/SQL 수, 백분위 재계산, 동일 응답, 6개 실제 test, 162개 plan, 입력 해시 동일성을 검증했다. 이번 실행에는 런타임 오류나 재시도가 없었다. 앞선 smoke/P2 재현·실패 증거는 그대로 보존했다.
- 측정 runner·Gradle/test Java 프로세스는 종료했다. 마지막 DB 확인에서 다른 ct_improve_a 세션은 없었고 전용 `ct-improve-a-20261111`은 127.0.0.1:55431에서 실행 상태로 보존했다. benchmark 스키마와 마지막 fixture도 보존했다. 앱 18081/fake HTTP 19081 서버는 만들지 않았다.

이로써 A의 배정된 2차 비교 측정과 plan 분석을 마쳤다. 후속 B/C/D 통합·전체 검증·사용자 게시 승인은 별도이며, 이 슬롯에서 전체 회귀·다른 작업 성능·게시를 수행하지 않았다.

## 2026-11-13 덧붙임 — PR 분리 후 읽는 법

위 본문은 2026-11-11 당시 기록 그대로다. PR로 분리하면서 다음만 덧붙인다.

- 본문의 `build/availability-*` 경로와 조율 보고서 `reports/review-ab-standards.md`는 당시 로컬 작업트리·조율 폴더의 위치이며 저장소에 없다.
- `perf/availability-batch`는 당시 로컬 작업 브랜치다. 이 변경은 `perf/reservation-slot-batch`에서 `dev`로 올린다.
- 본문의 "V38"은 당시 검토한 추가 인덱스 마이그레이션의 가칭 번호다. 추가하지 않았고 이 PR에 마이그레이션은 없다.
- 분리본의 검증 결과는 [2026-11-13 분리 검증 기록](2026-11-13-availability-pr-split.md)에 있다.
