# 영업시간 검증 최적화 PR 분리와 독립 검증

- 작업일: 2026-11-13
- 대상: `perf/business-hours-snapshot` → `dev`
- 분리 기준: `4ac98d2034dfe17d80836667480c7d38459479ab`
- 관련 명세: [Notion 영업시간 관리](https://app.notion.com/p/3d548d76fa3281bfa916f1cf77badc39), [프로젝트 기준](../docs/PROJECT.md)
- 이전 구현·측정 기록: [2026-11-11 기록](2026-11-11-business-hour-validation.md)

## 배경과 범위

백엔드 개선 합본을 다섯 PR로 나눈 것 중 B(영업시간 변경 검증)를 독립 검토할 수 있도록 합본의 B 변경만 분리했다. 제품 2파일·B 테스트/fixture 3파일·성능 도구 4파일·기존 작업 기록 1파일을 추출하고 이 기록을 추가했다. 여기에 예약 가능 시간 PR(A)과 같은 공통 테스트 보정 3파일(Clock fixture 2, context-cache 1)을 더해 변경 범위는 총 14파일이다.

분리본만으로 신규 전용 DB에서 전체 build를 1회 실행했다. **97클래스 1,097건, 실패·오류·skip 0**이며 신규 snapshot 9건과 실제 DB 동시성 12건을 포함한다. 별도 관련 회귀나 대규모 성능을 반복하지 않았다. 이 기록은 2026-11-13 로컬 검증 기준이며 CI·프런트 연동·배포는 확인 범위 밖이다.

## 변경과 판단

`validateActiveReservationsCovered`는 활성 예약을 한 번 읽고 예약 시작일과 전날에 필요한 설정을 검증 호출 안에서 재사용한다. 예약이 없으면 설정 조회를 생략하고, 예약이 있으면 요일 설정 1회와 단일 매장 기간 특정일 설정 1회로 날짜별 구간을 만든다. 특정일 휴무 우선·전날 자정 넘김·마감/브레이크 경계·soft-delete는 기존 엔티티 계산을 사용한다. 범위가 넓으면 중간 날짜의 특정일도 읽으며 활성 예약 자체의 전체 로딩은 남는다.

호출 경로를 정적으로 대조했다. 요일 교체는 `findByIdForUpdate` → 변경 → `flush` → 공통 검증이다. 특정일 저장·수정은 `lockManagedStore` → `saveAndFlush` → 공통 검증이며, 삭제는 같은 잠금 → soft-delete → `flush` → 공통 검증이다. 예약 생성의 `findByIdForShare` → 검증·저장 순서와 같은 트랜잭션 롤백 경계는 바꾸지 않았다. `SpecialBusinessDayService` 제품 변경 없이 저장·수정·설정삭제에 최적화가 적용된다.

두 제품 파일과 신규 B 테스트/fixture 3파일 및 benchmark Java/Gradle 파일은 합본과 바이트 단위로 같게 추출했다. 지도 기능의 `Collection` import와 `findAllByStoreIdInAndBusinessDateBetween` 메서드도 보존한다. A의 일괄 가용성 조회, C 이력, D PG 호출 격리, E durable SSE 코드·스키마·테스트를 추가하지 않았다. 공개 조회/API·웨이팅 검증·예약 활성 조건도 이번 변경 범위 밖이다.

성능 도구의 비교 기준을 이번 분리 기준 커밋으로, 실행 대상을 전용 자원 `ct-pr-b-20261113` / `ct-pr-b-20261113-data` / `localhost:55442/ct_pr_b` / 앱 `18092` / fake `19092`로 바꿨다. 계측·표본·집계 알고리즘은 바꾸지 않았다. 계정은 일회용 로컬 기본값이며 외부 인증 토큰은 포함하지 않는다. 실행 안내와 과거 기록에는 과거 결과/현재 미실행을 구분했다.

## 검증

| 확인 내용 | 방법 | 상태 |
| --- | --- | --- |
| 합본 원본 보존 | 분리 전후 589개 파일의 SHA-256·크기·모드 대조 | 589개 일치, 원본 변경 없음 |
| B 추출 범위 | 원본/대상 hash, 허용 목록, 다른 tracked 파일의 시작 fingerprint 대조 | B 7파일·공통 3파일 원문 동일, 제품 변경은 B 2파일뿐 |
| 문법·링크·diff | Python `ast.parse`, Java import 대상/괄호 정적 검사, 상대 링크 검사, `git diff --check` | 컴파일이나 테스트 통과를 뜻하지 않음 |
| 신규 검증 | 전체 build 원본 XML에서 snapshot·실제 DB 동시성 집계 | 9건·12건 통과, 별도 반복 없음 |
| 공통 보정 | A PR과 같은 patch의 hash·적용 전/후 3파일 지문 확인 | 2개 before 일치·1개 부재 → 3개 after 일치, 1회 적용 |
| Java 컴파일·전체 build | 전용 DB, 아래 명령 1회 | exit 0, 97클래스 1,097건 실패·오류·skip 0 |
| 기존 회귀 포함 | 같은 전체 build XML 목록 대조 | 예약 344·PG 188·지도 관련 102건 포함, 그룹별 수는 중복 없는 도메인 집계 |
| fresh migration·JAR | SQL SHA·DB history checksum·bootJar entry 검사 | 기존 SQL 34개 V1~V35 일치, 새 migration 0, B 제품 포함·시험 코드 제외 |
| 성능·별도 benchmark task | 과거 환경 지문과 현재 서비스/fixture/계측 코드 대조 | 새 측정·별도 benchmark 컴파일 미실행 |
| CI·프런트 연동·배포 | 이 로컬 검증 범위 밖 | 미확인 |

2026-11-11 B 회귀·성능과 합본 1,204건 통과는 과거 증거다. 이 분리본의 새 통과·새 성능값으로 사용하지 않는다. 당시 로컬 원시 증거와 분리 검산 파일은 저장소에 없으므로 PR에는 이 기록과 포함 코드/도구를 연결한다.

## 공통 테스트 보정

A PR과 같은 공통 테스트 보정 patch를 1회 적용했다. `BusinessHourWaitingConflictTest`는 서비스/fixture Clock 일치, `WaitingServiceTest`는 고정 Clock·스케줄러 간격, `src/test/resources/spring.properties`는 context cache 상한만 포함한다. 적용 전 두 파일 SHA는 각각 `e53870908b38018e0161509e2e5b715384eebe9e9b2f9e7553261ec8f5a1a3d5`, `ca7b1472e606dd2dd29d7d12be8605b76fa1b0a935f01275a146589e7686d9fb`였고 `spring.properties`는 부재했다. 적용 후 SHA는 각각 `dab010a3cfef750b1b5bfddeb40956d3327cbe9d0d5d5f9a63be1a5f95eced13`, `298e25469d37ffdbc70387616ad225ab72137ef0af9cfd046f1653100a6bc009`, `b75bd5069251d3cb65c9722f13d96a9cf91f858e039603704cbe763d428badf7`로 A PR의 같은 파일과 일치한다. 합본의 이력·PG·SSE 정리 SQL이나 scheduling 설정은 가져오지 않았다.

이 3파일은 B 기능과 분리한 별도 commit이며 A PR과 같은 commit을 공유한다. 한쪽이 merge commit으로 먼저 `dev`에 들어가면 다른 쪽 변경분에서 빠진다.

## 분리본 전체 build (2026-11-13)

컨테이너·볼륨 부재와 포트 부재를 확인하고 전용 `ct-pr-b-20261113` / `ct-pr-b-20261113-data`를 생성했다. 캐시 PostgreSQL 이미지 `sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54`를 `--pull=never`로 사용하고 loopback `55442/ct_pr_b`를 지정했다. 기존 업무 DB에 연결하지 않았다. 실행 전 public 테이블 0개였고 PostgreSQL 16.15, Java Temurin 21.0.12.1, `ddl-auto=validate`를 사용했다. `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m'`이며 외부 공급사는 로컬 fake 주소/시뮬레이션 설정으로 제한했다. 앱/fake 서버를 따로 시작하지 않았다.

```sh
./gradlew build --offline --no-daemon --max-workers=2 \
  --no-build-cache --rerun-tasks \
  -Dorg.gradle.java.installations.auto-download=false
```

2026-11-13 01:41:32~01:42:20 KST, 명령 보존 구간 약 47.9초, exit 0이다. 원본 XML/HTML·전체 로그를 저장소 밖 로컬 경로에 복사 보존하고 XML 97개·실제 testcase 1,097개를 직접 집계했다. snapshot 9·동시성 12·기존 웨이팅 충돌 8·특정일 서비스 13·예약 설정 42·예약 동시성 6건이 포함된다. 신규 동시성 12건은 서로 다른 backend PID, `pg_blocking_pids`의 실제 Lock 대기, 선행 commit/rollback 뒤 예약·설정 변경 거절/허용과 최종 상태를 assertion으로 검증한다. 전체에 포함된 검사를 다시 실행하지 않았다.

기존 migration SQL 34개는 기준 커밋·합본·현재 파일 SHA가 같고 새 migration은 없다. fresh DB history 34개가 모두 success이며 각 checksum을 현재 SQL의 줄 단위 CRC32와 대조해 일치했다. bootJar의 SQL 34개 바이트도 소스와 같다. `build/libs/catchtable-0.0.1-SNAPSHOT.jar`에는 B 제품 두 클래스가 있고 시험/fixture/계측 클래스와 공통 시험용 `spring.properties`는 없다. JAR·원본 XML은 로컬 산출물이며 이 PR에 바이너리나 raw dump로 넣지 않는다.

과거 B 본 측정의 after 환경 파일과 비교해 서비스·fixture·benchmark Java·Gradle의 SHA가 같음을 확인했다. 저장소 파일의 차이는 기존 지도 기능의 `Collection` import·다중 매장 메서드뿐이며 B 단일 매장 메서드는 같다. 실행 도구는 BASE/예약 자원 상수만 바뀌고 계측 알고리즘은 같다. 따라서 과거 16,200기록은 역사적 근거로만 연결하고 새 성능 측정이나 smoke를 반복하지 않았다. 성능 명령·지표 한계는 [실행 안내](../scripts/performance/business-hour-validation/README.md)에 유지했다.

01:44 KST에 14파일의 실행 전후 지문과 HEAD·브랜치·index 보존을 확인했다. 자기 JVM·앱18092/fake19092 listener·JDBC 세션·B fixture 매장 잔존은 모두 0이다. DB/volume은 보존했다. 이후에는 문서만 갱신했다.

## 2026-11-13 덧붙임 — PR 리뷰 반영 (계측 도구)

PR의 자동 리뷰 지적 3건을 계측 도구에 반영했다. 제품 코드와 테스트는 바꾸지 않았다.

- `run.py`가 호출 환경을 그대로 Gradle에 넘겨, `SPRING_DATASOURCE_URL` 같은 값이 있으면 전용 DB가 아닌 곳에 fixture 쓰기와 Flyway가 실행될 수 있었다. 상속된 `SPRING_*` 환경변수를 제거한 뒤 전용 `DB_*` 값을 넣도록 했다.
- `summarize`가 before/after 환경 파일의 규모·워밍업·표본·반복 값을 비교해 다르면 집계를 거부한다.
- Java 탐색은 그대로 두고, macOS가 아니면 `JAVA_HOME`을 직접 지정해야 한다는 점을 [실행 안내](../scripts/performance/business-hour-validation/README.md)에 적었다.

확인은 Python 구문 검사와 로컬 임시 폴더에서의 함수 단위 실행이다. 환경변수 제거와 전용 값 유지, 설정이 다를 때 거부, 같을 때 집계를 확인했다. 도구를 DB에 대해 실행하지는 않았고 새 성능 측정도 없다. 위 본문의 "실행 도구는 상수만 바뀌었다"는 설명은 이 덧붙임 이전 시점 기준이다.
