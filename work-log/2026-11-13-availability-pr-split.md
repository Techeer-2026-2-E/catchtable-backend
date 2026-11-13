# 예약 가능 시간 최적화의 독립 PR 분리 검증

- 작업일: 2026-11-13 KST
- 기준: `dev`의 `4ac98d2034dfe17d80836667480c7d38459479ab`, 브랜치 `perf/reservation-slot-batch`
- 명세 연결: [예약 가능 시간·잔여 테이블 조회](https://app.notion.com/p/3d548d76fa32817389b8dee3a96d5350). 기존 기록의 정본 링크이며 이번 작업에서 외부 명세를 다시 읽거나 수정하지 않았다.
- 이전 구현·측정: [2026-11-11 A 기록](2026-11-11-availability-batch.md)

## 배경과 범위

백엔드 개선 합본을 다섯 PR로 나눈 것 중 예약 가용 수량 일괄 조회(A)만 현재 dev 기준으로 분리했다. 제품 변경은 `ReservationAvailabilityService`와 `StoreTableRepository`, 새 `StoreTableAvailabilityRepository` fragment 및 구현의 4파일이다. B 영업시간 snapshot, C 이력, D PG 호출 격리, SSE durable 기능은 포함하지 않는다. 통합본의 과거 1,204건 통과는 이번 분리본의 실행 근거로 사용하지 않는다.

슬롯을 먼저 정렬·중복 제거하고 하나의 SQL에서 수량을 구한다. 슬롯 순서와 수량, 자정·전날 밤 영업·특별 영업일·휴무·브레이크·예약 마감 규칙을 보존한다. 기존 단건 수량과 같은 활성·인원·선점 만료·승인 진행/결과 불명/격리 보호, `[)` 시간 겹침 조건을 사용한다. 빈 입력은 SQL을 실행하지 않는다. 조회는 한 statement snapshot의 순간값이며 실제 예약 생성의 행 잠금·시간 중복 제약을 대신하지 않는다. HTTP 요청·응답·상태 계약, 실제 예약 생성·결제 처리·지도 기능은 바꾸지 않는다.

## 공통 테스트 전제

합본 작업 중의 로컬 검증은 자정 이후 `BusinessHourWaitingConflictTest.closingTodayWithWaitingRollsBack`, `WaitingServiceTest.create_specialClosedDay_fails`가 기준본에서도 실패했다고 기록했다. 당시 실패는 보존한다. 오늘 특별 휴무와 전날 밤 영업의 연장을 혼동하던 `지금 ±1시간` fixture를 서비스와 같은 정오 Clock으로 고정한 합본의 수정만 재사용했다. `WaitingServiceTest`의 예약된 만료 실행 간격도 늘려 시험 중 scheduler 경합을 줄였고, test-only `spring.properties`의 context cache를 4로 제한해 cached Hikari pool 수를 제한했다.

이 공통 보완은 위 테스트 2개 파일과 `src/test/resources/spring.properties` 안에 한정된다. 제품·scheduler 정책·기대값·시험 수를 변경하지 않으며 테스트 삭제·skip은 없다. A 제품 변경과 분리한 별도 commit이며 영업시간 검증 PR(B)과 같은 commit을 공유한다. 한쪽이 merge commit으로 먼저 `dev`에 들어가면 다른 쪽 변경분에서 빠진다.

## 이번에 실행한 검증

Java Temurin 21.0.12.1, 기존 Gradle 9.7.1 캐시, 캐시된 PostgreSQL 16.15 이미지를 사용했다. 새 컨테이너 `ct-pr-a-20261113`, 새 volume `ct-pr-a-20261113-data`, `127.0.0.1:55441/ct_pr_a`, 새 `pa_full_20261113_01` schema만 사용했다. 컨테이너·volume의 작업 소유 label과 loopback binding을 확인했다. 합성 로컬 계정과 비밀번호를 별도로 주입했고, 실제 `.env`·Toss·SOLAPI·기존 DB는 사용하지 않았다. JPA `ddl-auto: validate`와 Flyway는 기존 설정 그대로다.

```bash
# 아래 DB 변수에는 위 전용 DB의 합성 로컬 접속 정보를 주입한다.
# 실제 실행에는 application.yml 기본값과 같은 ALIMTALK_MODE=simulation과
# 빈 SOLAPI_WEBHOOK_SECRET·SOLAPI_CHANNEL_ID·SOLAPI_TEMPLATE_ID도 넣었다.
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m'
export SPRING_APPLICATION_JSON='{"spring":{"datasource":{"hikari":{"data-source-properties":{"currentSchema":"pa_full_20261113_01,public"}}}}}'
DB_HOST=127.0.0.1 DB_PORT=55441 DB_NAME=ct_pr_a DB_USERNAME=ct_pr_a \
DB_PASSWORD="$PR_A_DB_PASSWORD" SERVER_PORT=18091 \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:19091 ALIMTALK_ENABLED=false \
SOLAPI_API_KEY= SOLAPI_API_SECRET= SOLAPI_BASE_URL=http://127.0.0.1:19091 \
./gradlew build --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks \
  -Dorg.gradle.java.installations.auto-download=false --console=plain
```

| 검사 | 실제 결과 |
| --- | --- |
| 전체 build 1회 | exit 0, Gradle 50초, 8개 actionable task 모두 executed |
| XML 전체 집계·소스 대조 | 96클래스·1,095건, 실패/오류/skip 0. 시험 annotation이 있는 소스 96개와 XML 클래스 일치, 원시 로그의 PASSED 1,095개와 일치 |
| A 신규 / 기존 가용성 / 생성 경합 | 19 / 2 / 6건 통과. 신규 시험에서 단건 대비 동등성, 27슬롯 수량 SQL 1회·빈 입력 0회 확인 |
| 기존 예약·PG 보호 | 예약 시간 검증 2·설정 42·예약 결제 46·결제 재확인 15·결제 재확인 경합 3·멱등성 경합 4·미확정 환불 outbox 20·scheduled 1건 포함 |
| 공통 fixture 대상 | 영업시간/웨이팅 충돌 8·웨이팅 신청 28건 통과. 과거 baseline 실패 2건의 대상 클래스 포함 |
| 지도 보존 | 지도 반경 24·범위 36·상태 응답 6·영업상태 계산 12건, 합계 78건 통과. 관련 소스는 BASE와 동일 |
| fresh migration | 기존 SQL 34개(V1~V35, V23은 원래 없음)의 BASE 대비 SHA 불변, 새 migration 0. Flyway 34행 모두 success, 파일 CRC32와 DB checksum 일치 |
| bootJar | main compiled class 291개와 패키징 클래스 집합·바이트 일치. main resource·migration 원문 일치. test/benchmark 클래스와 test-only spring.properties 제외 |
| 도구·문서 | Python 구문·CLI help, init script의 up-to-date/cache 비활성·fork 1 및 반복 옵션 정적 확인, 문서 링크·patch·공백 검사 |

표의 개수는 전체 1,095건의 포함 관계이며 합산하지 않는다. 별도 새 CI·프런트·실제 공급사·배포 검증은 하지 않았다. 전체 build는 1회 성공했고 재시도·범위 내 제품 수정은 없었다. DB 기동 중 첫 readiness probe exit 2와 다음 성공은 별도 보존했다. 종료 감사의 첫 컨테이너 비교는 자동 갱신되는 기존 컨테이너의 health Log 시각까지 같아야 한다고 잘못 검사해 실패했다. 실패 자료를 보존하고 ID·이미지·mount·시작/종료 시각·상태·health 상태를 다시 대조해 불변을 확인했다. 기존 자원은 변경하지 않았다.

## 과거 성능 증거의 재사용 범위

이번 A 제품 4파일은 2026-11-11 2차 성능 측정의 입력 SHA와 모두 같다. [이전 기록의 조건·수치·EXPLAIN 및 한계](2026-11-11-availability-batch.md#2026-11-11-2차--배정된-순차-성능-측정과-실행-계획)를 재사용하며 대규모 측정을 반복하지 않았다. 전체 앱 입력에는 이후 dev 지도 변경과 공통 fixture 차이가 있으므로 현재 분리본의 새 성능 실측이라고 표현하지 않는다.

이전 기록의 `build/availability-*` 경로는 당시 원본 작업트리의 로컬 증거 위치이며 이 PR에 포함된 파일 링크가 아니다. 현재 도구 runner도 당시 `ct-improve-a-20261111:55431`와 고정 schema용이다. 이번 전용 DB로 재실행하는 도구라고 간주하지 않고, 보존 중인 옛 DB에 실행하지 않았다. 도구 실행 코드는 합본과 동일해 새 런타임 smoke는 필요하지 않았고, README에 이 범위를 덧붙였다. 하나의 SQL이 DB 내부 탐색도 한 번이라는 의미는 아니다.

## 근거 접근 범위

PR에 포함한 근거는 이 기록, [기존 A 기록](2026-11-11-availability-batch.md), [도구 설명](../tools/availability-benchmark/README.md), 제품 코드와 테스트다. 전체 로그·XML·실행 명령/환경·SHA manifest·source archive·DB dump는 저장소 밖 로컬 검증 산출물로 보존하며 이 저장소에서 열 수 있는 링크가 아니다.

분리 전후로 합본 589파일을 대조하고 A 외 제품·기존 migration·설정·지도 소스의 불변을 검사했다. 실행 전후 소스와 index 지문을 비교했으며 build 산출물·로컬 지침·비밀·원시 로그는 PR 변경에 포함하지 않았다. 자기 JVM/Gradle/fake/JDBC가 종료된 것을 확인하고 전용 DB·volume·dump는 보존했다.

이 기록은 커밋 전 로컬 검증 시점의 결과다. CI와 리뷰 결과는 PR에서 확인한다.

## 2026-11-13 덧붙임 — 비교 도구의 환경변수 격리

영업시간 검증 PR의 자동 리뷰에서 계측 도구가 호출 환경을 그대로 Gradle에 넘긴다는 지적이 있었고, 이 도구도 같은 구조라 함께 고쳤다. `SPRING_DATASOURCE_URL` 같은 값이 있으면 전용 DB가 아닌 곳에 fixture 쓰기와 Flyway가 실행될 수 있었다. `run.py`가 상속된 `SPRING_*` 환경변수를 제거한 뒤 전용 `DB_*` 값을 넣는다. 제품 코드와 테스트는 바꾸지 않았다.

확인은 Python 구문 검사와 `--help` 실행이다. 도구를 DB에 대해 실행하지는 않았다. 위 본문의 "도구 실행 코드는 합본과 동일"이라는 설명은 이 덧붙임 이전 시점 기준이다.
