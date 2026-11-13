# 웨이팅 업무·알림톡 이력 PR 분리 검증

- 작업일: 2026-11-13 KST
- 기준: 예약 가능 시간 PR(#271)과 영업시간 검증 PR(#272)이 반영된 `dev`의 `4b4d4aa0382a94d23e037b1e91fa721494aa1c4e`, 브랜치 `feat/waiting-event-log`. 빌드는 두 PR을 미리 합쳐 내용이 같은 로컬 병합본에서 실행했다.
- 관련 명세: [호출 알림 명세](https://app.notion.com/p/3d548d76fa3281dba92edea0ce84ce29). 기존 기록에 있던 링크이며 이번 작업에서 다시 읽거나 수정하지 않았다.
- 이전 구현·측정: [2026-11-11 기록](2026-11-11-waiting-event-history.md)

## 배경과 범위

백엔드 개선 합본을 다섯 PR로 나눈 것 중 세 번째다. 웨이팅 상태 변경과 알림톡 outbox 변화를 `waiting_event` 테이블(V36)에 남기는 부분만 담았다. 뒤따르는 PG 호출 격리(V37)와 웨이팅 SSE 영속화(V38·V39)는 들어 있지 않다.

| 구분 | 내용 |
| --- | --- |
| 제품 코드 9파일 | 새 `WaitingEventReference`, `WaitingEventSnapshot`, `WaitingEventRepository`, `WaitingEventRecorder`. 수정 `WaitingService`, `OwnerWaitingService`, `WaitingExpiryScheduler`, `CustomerNotificationEvent`, `WaitingQueueChangedEvent` |
| 마이그레이션 | V36 추가. 기존 V1~V35 파일은 바꾸지 않았다 |
| 테스트 7파일 | 새 `WaitingEventHistoryTest`, `WaitingEventMigrationTest`, `WaitingSseObservationRecorder`(테스트 전용)와 그 테스트. 수정 `AlimtalkHttpFlowTest`, `WaitingDeferServiceTest`, `WaitingExpirySchedulerTest` |
| 도구·기록 | [이력 조회 SQL](../scripts/waiting-event-history.sql), [2026-11-11 비교 도구](../scripts/benchmarks/waiting-history/README.md) 9파일, 2026-11-11 기록, 이 기록 |

접수·호출·재호출·응답·연기·착석·취소·노쇼·자동 만료·종료 사유 정정이 실제로 받아들여질 때, 같은 트랜잭션 안에서 사건 한 건을 기록한다. 이력 저장이 실패하면 상태 변경도 함께 롤백된다. 알림톡은 V36 trigger가 outbox 행의 상태 변화를 같은 트랜잭션에서 기록한다. 알림톡 전송 로직과 V32 outbox, HTTP 응답은 그대로다. `/call`·`/recall`에 HTTP 멱등키를 추가하지 않았고, 받아들인 요청은 각각 별도 사건이다. 과거 자료를 추정해 채우는 backfill은 없다.

**SSE 전송은 바꾸지 않았다.** `WaitingQueueNotifier`, `NotificationService`, `NotificationEventListener`, `SseEmitterRepository`는 기준 커밋과 같고, 커밋 후 동기 전송도 그대로다. 두 이벤트 DTO에는 사건 참조(`waitingEvent`) 필드만 더했다. 외부로 나가는 SSE payload에는 이 값이 들어가지 않는다.

## 합본과 다른 중간 형태

이 PR만으로 빌드와 테스트가 되도록, 합본에서 SSE 영속화에 속한 부분을 뺀 형태로 넣은 파일이 7개 있다. 나머지 21개는 합본과 내용이 같다.

| 파일 | 이 PR의 형태 |
| --- | --- |
| `CustomerNotificationEvent`, `WaitingQueueChangedEvent` | `waitingEvent` 필드와 `withWaitingEvent`, 기존 생성자 호환만 있다. 내부 전달 키(`deliveryKey`)는 없다 |
| `WaitingEventHistoryTest` | 20개 중 업무·알림톡·조회 15개. SSE 전달 원장을 쓰는 5개는 없다 |
| `WaitingDeferServiceTest`, `WaitingExpirySchedulerTest` | 기존 이벤트 비교에서 `waitingEvent`만 제외한다. 발행 건수와 payload 검증은 그대로다 |
| 2026-11-11 기록, 비교 도구 README | 아직 저장소에 없는 문서를 가리키던 안내 문단을 2026-11-13 안내로 바꿨다. 나머지 원문은 사용자 폴더 절대 경로를 상대 경로로 바꾼 것 말고는 그대로다 |

`WaitingSseObservationRecorder`는 2026-11-11 당시 제품 코드에 있던 메모리 관찰 writer를 테스트 전용 fixture로 남긴 것이다. 당시 저장 방식의 회귀 6건과 묶음 저장 실패 1건을 검증하는 데만 쓴다. 제품 코드에 연결하지 않았고 bootJar에도 없다.

## 검증

Java Temurin 21.0.12.1, 기존 Gradle 9.7.1 캐시, 캐시된 PostgreSQL 16.15 이미지를 썼다. 이 작업 전용으로 새로 만든 컨테이너 `ct-pr-cde-20261113`(`127.0.0.1:55443`)의 빈 데이터베이스 `ct_pr_cde_c_1`에서 Flyway가 처음부터 실행됐다. 합성 로컬 계정만 넣었고 실제 `.env`·Toss·SOLAPI·기존 DB는 쓰지 않았다. `PATH`·`HOME` 같은 기본 변수 말고는 호출 환경의 변수를 Gradle에 넘기지 않았다.

```bash
# DB_* 에는 위 전용 DB의 합성 로컬 접속 정보를 넣는다.
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m'
DB_HOST=127.0.0.1 DB_PORT=55443 DB_NAME=ct_pr_cde_c_1 DB_USERNAME=ct_pr_cde \
DB_PASSWORD="$PR_C_DB_PASSWORD" SERVER_PORT=18093 \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:19093 \
ALIMTALK_ENABLED=false ALIMTALK_MODE=simulation \
SOLAPI_API_KEY= SOLAPI_API_SECRET= SOLAPI_WEBHOOK_SECRET= SOLAPI_CHANNEL_ID= SOLAPI_TEMPLATE_ID= \
SOLAPI_BASE_URL=http://127.0.0.1:19093 \
./gradlew build --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks \
  -Dorg.gradle.java.installations.auto-download=false --console=plain
```

| 검사 | 결과 |
| --- | --- |
| 전체 build 1회 | exit 0, Gradle 50초, 8개 task 모두 실행. 재시도와 수정은 없었다 |
| JUnit XML 직접 집계 | 101클래스 1,138건, 실패 0·오류 0·건너뜀 0. 테스트 annotation이 있는 소스 101개와 XML 클래스가 일치하고 로그의 PASSED 1,138줄과 일치 |
| 이번에 추가·수정한 테스트 | `WaitingEventHistoryTest` 15, `WaitingSseObservationRecorderTest` 6, `WaitingEventMigrationTest` 1, `AlimtalkHttpFlowTest` 1, `WaitingDeferServiceTest` 9, `WaitingExpirySchedulerTest` 3 |
| 바꾸지 않은 SSE 전송 테스트 | `OwnerWaitingServiceTest` 51, `OwnerWaitingControllerTest` 19, `WaitingQueueNotifierTest` 7, `NotificationServiceTest` 6, `NotificationEventListenerTest` 4, `NotificationRequiredFirstTest` 8, `NotificationSettingServiceTest` 8, `SseEmitterRepositoryTest` 6, `NotificationControllerTest` 6, `OwnerReservationFeatureServiceTest` 4. 파일은 기준 커밋과 같다 |
| 빈 DB의 Flyway | 35행(V1~V36, V23은 원래 없음) 모두 success. 각 checksum이 파일의 줄 단위 CRC32와 일치 |
| V35 → V36 | `WaitingEventMigrationTest`가 V35까지 적용한 스키마에 자료를 넣고 V36을 적용해, 기존 자료가 그대로이고 `waiting_event`가 0행임을 확인 |
| bootJar | main 클래스 296개가 컴파일 결과와 같고 마이그레이션 35개 포함. 테스트 전용 fixture와 테스트용 `spring.properties`는 없다 |
| 범위 | SSE 영속화·PG 격리의 클래스, 테이블, 설정을 쓰는 코드가 없음을 소스 검색으로 확인 |
| 문서 | 바뀐 Markdown의 상대 링크가 이 커밋의 트리에서 모두 열림. `git diff --check` 통과 |

표의 클래스별 건수는 전체 1,138건에 포함된 수이며 따로 더하지 않는다. CI·프런트 연동·실제 SOLAPI·배포는 이 로컬 검증에 없다.

## 남은 점

- 2026-11-11 기록의 294건과 p95 +12.35%는 제품 코드에 메모리 관찰 writer가 있던 당시 고정본의 결과다. 이 PR의 측정값이 아니며 새로 측정하지 않았다. 비교 도구는 당시 고정 입력만 받는다.
- 2026-11-11 기록과 비교 도구 README의 `build/…` 경로는 당시 로컬 증거 위치다. 이 저장소에는 없다.
- V36을 적용한 뒤에는 이력이 쌓이기 시작한다. 코드를 되돌려도 `waiting_event` 자료와 trigger는 남으므로, 되돌릴 때는 자료를 보존한 채 따로 판단해야 한다.
- 이력 조회 SQL은 허가된 DB 운영자용이다. 앱의 권한 검사를 대신하지 않는다.
