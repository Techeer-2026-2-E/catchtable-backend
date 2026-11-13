# 웨이팅 통합 이력 구현과 회귀 검증

> 2026-11-12 후속: 아래294개·누락0·+12.35%는 과거 V36 C batch 고정본 결과다. 현재 영속 SSE는 제품 관찰 writer를 제거한 V38/V39 구현이며, 현행 전달·검증 경로와 고정 입력 guard는 [문서·도구 보완 기록](2026-11-12-backend-review-docs-tools.md)을 따른다. 아래 당시 기록은 보존한다.

- 기준: `a44952e37993e5003c860714f126057cd311dba5`, 로컬 `feat/waiting-event-history`.
- 범위: 개선 C의 1차 구현·회귀/장애/동시성 검증 및 2차 순차 성능 측정.
- 최신 상태: batch 보완 관련294개 테스트/bootJar 성공, 동일 본 조건 SSE31,200행과 전체35,400행 누락0, p95 오버헤드+12.35%; I 재통합/공통 검증은 별도다. 원래 손실 결과와 준비 기록은 보존한다.
- 관련 원본: [호출 알림 명세](https://app.notion.com/p/3d548d76fa3281dba92edea0ce84ce29), [V32 구현 기록](250-waiting-alimtalk.md).
- 1차에서 외부 Notion은 읽기만 수행했다. 2차는 외부 조회 없이 수행했다. 이번 로컬 구현은 해당 문서의 기존 완료·배포 상태를 바꾸지 않는다.

## 확인한 기존 동작과 선택

`Waiting.call`은 최초 호출 및 CALLED 상태의 새 호출 모두 호출 횟수·시각·기한을 갱신하고 응답을 초기화한다. `/recall`은 재전송 누적 횟수·시각만 갱신하고 기한과 응답을 유지한다. `recallCount`는 호출별로 초기화되지 않는 기존 누적값을 보존했다. 고객 응답은 CALLED 상태를 유지하며, 같은 호출의 중복 응답은 변경하지 않는다.

원본 종료·연기 이력은 이미 있었고, V32에는 outbox 행에 `attempt_count`·현재 상태·공급사 message ID·오류가 있다. 현재 기준에는 별도의 시도 이력 테이블이 없다. 이번에는 outbox 상태가 실제 바뀔 때 그 관찰을 새 이벤트에 추가하며 기존 행은 계속 원본으로 사용한다.

관리자 답변 `msg_f9c43f807e90`에 따라 기존 `/call`·`/recall`에서 수락한 별도 동작은 각각 기록한다. 요청 멱등키가 없으므로 HTTP 재시도와 새 호출 의도를 구분할 수 없다. **이번 변경은 HTTP 요청 자체의 멱등성을 추가하지 않는다.** 향후 그것이 필요하면 요청 식별자·재사용 범위·응답 재생 계약을 별도로 정해야 한다.

## 구현과 원자성

V36에 `waiting_event`와 `(waiting_id, occurred_at, id)` 인덱스, `event_key` 유일 제약을 추가했다. 기존 마이그레이션은 수정하지 않았다. `ddl-auto: validate`를 유지한다. 기존 자료로 추정한 과거 사건을 backfill하지 않는다.

- 접수, 최초 호출, 새 호출, 기한 유지 재전송, 고객 응답, 연기, 연기로 자리가 이동한 상대 팀, 착석, 고객/점주 취소, 노쇼/미응답, 자동 만료, 종료 사유 정정을 기록한다.
- 서비스가 실제 변경을 수락한 경우에만 현재 업무 트랜잭션에서 `WaitingEventRecorder` → JDBC INSERT를 수행한다. 호출·응답 시각과 차수, 기한, 상태, 연기 순서, 착석 테이블 등을 변경 전/후 스냅샷으로 보관한다. 호출로 초기화된 이전 응답도 남는다.
- 종료·연기 이벤트는 원본 history ID를 참조한다. 원본 자유 입력 사유는 기존 테이블에 보존하며 통합 이벤트 JSON에는 복제하지 않는다.
- 생성/호출 차수/누적 재전송 차수/호출별 응답/착석/원본 이력 ID를 안정적인 사건 키로 사용한다. 저장 재시도는 `INSERT ... ON CONFLICT DO NOTHING`으로 처리해 정상 업무 트랜잭션을 rollback-only로 만들지 않는다. 같은 시각의 여러 수락 호출도 별도 키다.
- 상태 변경, 기존 이력, 새 이벤트, enabled 상태의 알림톡 enqueue가 같은 트랜잭션에 속한다. 업무 이벤트 저장 실패는 전체 업무를 롤백한다. 연기 실패 시 두 팀의 순서 변경도 함께 롤백한다. 자동 만료 스케줄러는 실패 건을 미완료로 두어 다음 실행에서 다시 처리한다.
- 애플리케이션은 이벤트를 수정하지 않고 추가한다. DB 관리자 수정 금지/보존 기간 정책은 별도다. waiting 삭제 시 cascade는 기존 알림톡과 같은 관계 정리 방식이며 별도의 영구 감사 보존 기능으로 주장하지 않는다.

## SSE와 알림톡 관찰 경계

| 기록 | 관찰 의미 | 보장하지 않는 내용 |
| --- | --- | --- |
| `SSE_EMIT_STARTED` | 해당 전송 실행에서 보인 연결 수, 내부 업무 사건 연결 | 연결이 브라우저에서 실제 활성인지, 전송 완료 |
| `SSE_EMIT_FINISHED` | 시도 수·`send` 정상 반환/예외 수·불명/미시도 수 | 브라우저 수신·표시·고객 확인 |
| `ALIMTALK_PENDING` | 원본 outbox의 대기 상태 저장; 재시도 대기도 포함 | 공급사 호출/접수 |
| `ALIMTALK_PROCESSING` | 기존 원자 claim에 성공한 처리 시도 | HTTP가 실제 전송됐는지, 공급사 수신 |
| `ALIMTALK_ACCEPTED` | 기존 검증 로직이 공급사 접수를 확인 | 최종 전달·고객 수신 확인 |
| `ALIMTALK_SENT` | 기존 로직이 최종 성공 보고를 반영 | 고객 열람/ACK 및 실제 수신 시점 |
| `UNKNOWN`·`FAILED`·`SKIPPED` | 기존 outbox 상태와 오류·시도 차수 그대로 | UNKNOWN을 확정 실패로 추정 |

모든 전달 관찰의 `customerReceipt`는 `UNKNOWN`이다. `transportMode`로 simulation/solapi를 구분한다. SSE `send`는 servlet 연결 준비 전 내부 버퍼에 넣고 정상 반환할 수도 있으므로 `serverSendSucceeded`는 **서버 함수 정상 반환**만 뜻한다. 기존 SSE event ID·이름·payload·커밋 후 전송과 알림톡 HTTP/웹훅 처리 로직은 유지했다.

`CustomerNotificationEvent.waitingEvent`는 내부 상관관계다. 외부 `NotificationMessage`·HTTP DTO에는 넣지 않는다. 등록/호출/응답/착석/종료의 직접 알림에는 사건 키가 연결된다. 기존 순번 브로드캐스트는 수신 대상 waiting ID만 관찰하며, 정확한 원인 사건/호출 차수를 추정하지 않아 `businessEventKey`, call/recall count가 null이다. 최초 SSE 연결 handshake는 특정 웨이팅 사건이 아니므로 이 테이블에 넣지 않는다. 수신 설정에 의해 보내지 않은 선택 알림도 전달 성공으로 만들지 않는다.

SSE 관찰 저장은 단일 writer·최대 512개 큐로 별도 트랜잭션에서 처리한다. **전송은 기존 동기 AFTER_COMMIT 흐름 그대로**이며 새 큐는 기록만 담당한다. 초기 동기 REQUIRES_NEW 구현은 커밋 후 아직 반납되지 않은 연결과 중첩 queue 조회가 작은 풀을 점유해 동시성 테스트를 지연시켰다. 관찰 기록을 기다리지 않도록 수정한 후 기존 4개 연결 풀의 연기 동시성과 10명 동시 접수가 통과했다.

큐 포화·DB 장애·프로세스 종료로 관찰 기록이 빠질 수 있다. 그 경우 경고 로그를 남기고 업무·SSE를 계속 진행한다. START만 남거나 아무 관찰도 없으면 결과는 미확인이다. FINISH만 남을 수도 있으므로 START 부재를 전송 미시도로 단정하지 않는다. 같은 전송 실행의 기록 재시도는 `(message.eventId, phase)`로 중복을 막는다. 중복 listener가 실제로 또 전송한 경우에는 별도 전송 실행으로 기록하며, 기존 SSE 전송 자체를 exactly-once로 바꾸지 않는다. 큐 재생/ACK/Redis/Kafka는 추가하지 않았다.

알림톡 관찰은 V36의 outbox INSERT/UPDATE 트리거로 기존 enqueue·claim·결과·일괄 skip/recovery를 같은 트랜잭션에서 기록한다. `(status, attempt_count, provider_message_id, last_error_code)`가 바뀌지 않은 중복 보고/조회 예약 시각 변경은 이벤트를 추가하지 않는다. 변화가 있는 정당한 후속 관찰은 별도 사건이다. 트리거 기록 실패는 해당 outbox 변경도 롤백한다. 이미 HTTP가 실행된 뒤 결과 저장에 실패하면 기존 PROCESSING 복구가 UNKNOWN으로 남기고 자동 재발송하지 않는 경계를 검증했다. 보고 원본은 `OUTBOX_TRANSITION`이며 웹훅/조회/HTTP 응답 각각의 원문이나 고객 수신 시간을 새로 추정하지 않는다.

## 최소 조회 수단

신규 API 대신 [매개변수 조회 SQL](../scripts/waiting-event-history.sql)을 제공했다. **허가된 DB 운영자 전용**이며 SQL의 store 조건은 앱의 회원/점주 권한 검사를 대신하지 않는다. 실제 DB 역할·최소 조회 권한과 담당 매장 범위를 먼저 확인해야 한다. 이름·전화번호·알림 본문·자유 입력 사유는 선택하지 않지만 actor/테이블/웨이팅 ID도 내부 식별정보이므로 조회 결과를 공개 자료로 취급하지 않는다.

PostgreSQL PREPARE 또는 드라이버 bind로 `$1=waitingId`, `$2=storeId`, `$3=limit`을 넘긴다. 둘 중 ID가 맞지 않으면 빈 결과다. 결과 상한은 500, null limit 기본값 100, 1 미만은 1이다. 문자열 보간으로 사용자 입력을 SQL에 붙이지 않는다. 예시는 다음과 같이 SQL 파일을 PREPARE 본문으로 실행한 뒤 값을 바인딩하는 방식이다.

```sql
-- PREPARE waiting_timeline(bigint, bigint, integer) AS
--   <scripts/waiting-event-history.sql의 SELECT 본문>
EXECUTE waiting_timeline(123, 45, 100);
```

`occurred_at → source_rank → sequence_id → source_id`로 정렬한다. 동일 시각의 새 이벤트는 증가 ID로 안정적으로 정렬하지만, 이는 완벽한 분산 인과 순서를 보장하지 않는다. `recorded_at`은 DB 기록 시각이고 `occurred_at`은 업무 Clock/원본 시각이다. 상한으로 잘린 결과가 전체 이력이라고 주장하지 않는다.

- `EVENT_V36`: 신규 사건/관찰. source_type/id로 원본 출처를 확인한다.
- `ORIGINAL_END_HISTORY`, `ORIGINAL_DEFER_HISTORY`: 새 이벤트와 연결되지 않은 원본 이력을 원래 created_at으로 보여준다. 현재 waiting에서 과거 호출 차수를 추정하지 않아 차수는 null이다.
- `CURRENT_OUTBOX_SNAPSHOT`: 원본 outbox의 현재 상태를 updated_at에 보여주는 **가변 스냅샷**이다. 과거 전송 사건을 복원한 기록이 아니다. 신규 outbox 이벤트와 함께 보일 수 있으며 별도 origin으로 구분한다. V32의 누적 attempt_count를 가상의 개별 과거 시도로 펼치지 않는다.

## 검증

새 전용 PostgreSQL 16 컨테이너 `ct-improve-c-20261111`, `127.0.0.1:55433/ct_improve_c`, 일회용 로컬 계정만 사용했다. 모의 HTTP 서버는 최종 실행에서 할당 포트 `19083`을 사용한다. 실제 SOLAPI/Toss 호출·공유 DB·배포는 수행하지 않았다.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DB_HOST=127.0.0.1 DB_PORT=55433 DB_NAME=ct_improve_c \
DB_USERNAME=ct_improve_c DB_PASSWORD=ct_improve_c_local \
TOSS_SECRET_KEY= ALIMTALK_ENABLED=false WAITING_TEST_HTTP_PORT=19083 \
./gradlew test --tests 'com.catchtable.waiting.*' --tests 'com.catchtable.notification.*' \
  bootJar --no-daemon --max-workers=2 --console=plain
```

최종 관련 27개 클래스 286개(신규 통합 이력 18개·마이그레이션 1개 포함)가 실패/오류/skip 0으로 통과했고 bootJar가 성공했다. 로그는 `build/waiting-event-history/regression-complete.log`, 원본 XML은 `build/waiting-event-history/final-xml/`에 보존했다. 결과와 원본 경로는 관리 보고서 C에도 기록한다. 검증에는 기존 상태/API/권한 회귀, 동시 호출·응답·연기·취소·만료, 원자 롤백·기록 실패, 유일키 충돌 후 정상 커밋, 같은 시각의 호출/재전송 구별, 다중/무연결/끊긴 SSE, 관찰 큐 포화·기록 유실, 공급사 접수/결과 불명/최종 보고, V35→V36 원본 보존·backfill 없음, 출처별 제한 조회가 포함된다. 모의 HTTP→인증 웹훅 검사에서 접수와 최종 이력까지 확인했다.

초기 회귀의 실패는 보존했다. 1차 267개 중 6개 실패(관찰 동기 저장으로 인한 동시성 4개, 내부 correlation 추가로 event equals 1개, 기존 자정 fixture 1개), 중간 56개 중 2개 실패(추가 SSE 순번 전송을 누락한 검증 기대값과 자정 fixture), 286개 중 1개 실패(포화 큐 drain helper가 너무 빨리 barrier를 넣음)를 수정했다. 최종 성공으로 이 최초 실패 기록을 덮어쓰지 않는다.

자정 fixture는 기준 커밋을 자기 산출물 폴더로 추출해 같은 실패(`Expecting code to raise a throwable`)를 재현했다. 이전 영업일의 자정 넘는 ±1시간 fixture가 오늘의 휴무와 겹치므로 테스트에 정오 고정 Clock을 넣었다. 영업시간 서비스·정책은 변경하지 않았다. 기준 재현 최초 두 번은 독립 스키마의 확장 search_path/설정 문제로 컨텍스트 초기화가 실패했고, JDBC `currentSchema=baseline_c,public`으로 정리한 세 번째 실행에서 실제 동일 assertion을 확인했다.

## 1차 종료 시 남은 범위 (아래 2차 결과로 갱신)

[성능 비교 도구](../scripts/benchmarks/waiting-history/README.md)는 동일 소스 fixture·Clock·JVM/DB 설정·워밍업/표본/반복과 SQL·p50/p95/p99·오류율·잠금/연결/heap·원시 결과 수집을 준비했다. 1차에서 baseline/modified harness 컴파일만 확인했으며 부하 실행은 관리자 순차 배정 후다. 이벤트 보존 용량·관찰 큐 포화 임계값·SQL 비용은 아직 측정하지 않았다.

다른 담당 변경과의 전체 통합, 전체 저장소 CI, 실제 브라우저/공급사 수신, 공유 DB 마이그레이션·배포는 별도다. 커밋·push·PR·merge·외부 문서 편집을 하지 않았고 워크트리를 보존한다.

## 2차 측정 완료 — 2026-11-11, SSE 관찰 누락 발견

관리자의 독점 성능 슬롯 배정 후 로컬 도구를 보완하고 smoke→본 측정을 실행했다. 이번 단계는 **측정과 근거 보고를 완료**한 것이며, SSE 관찰 기록 품질의 수용 완료를 뜻하지 않는다. 제품/기존 테스트 415파일은 입력 시점과 SHA256이 동일하다. I 합본 1,075개 통과는 관리자가 전달한 결과이며 이 작업자가 다시 실행하지 않았고 I 원본/DB에 접근하지 않았다.

### 실제 실행과 도구 보완

외부 조회·다운로드·fetch/pull·커밋/push/PR·배포·실제 공급사 호출 없이 설치된 Java 21과 기존 Gradle 9.7.1 캐시를 직접 실행했다. compile/run에 `--offline --no-daemon --max-workers=2 -Dorg.gradle.java.installations.auto-download=false`를 적용했다. 기준은 로컬 git archive, 수정본은 C 소스 복사이며 앱/DB는 배정 자원만 사용했다. 전용 컨테이너만 pg_stat_statements preload/track=all 설정 후 재시작했다.

기존 도구는 runtime 미검증 상태였으므로 warmup=0 허용, 반복 실행 결과 디렉터리 분리, 파일 잠금, manifest 검증, 실제 실행 강제, 응답/전후 상태/큐 drain/SQL toplevel/원시 자원 기록을 보완했다. 비교 조건은 양쪽에 같은 harness로 적용했다. 최초 modified 본 실행의 사전 bind 검사에서 `Errno 48 Address already in use`가 발생했지만 실제 listener는 없었고 앱 시작/스키마 초기화 전에 중단됐다. 종료 소켓 상태에 대한 오탐으로 추정하여 SO_REUSEADDR만 추가한 뒤 성공했다(TIME_WAIT 상태 자체는 직접 포착하지 못함). 실패 로그를 보존했고, 별도 소켓 검사로 살아 있는 listener 거부와 해제 포트 허용을 확인했다. 제품 코드와 상태 기대값은 바꾸지 않았다.

본 측정 뒤 JUnit system-out에서 경고를 추출해 큐 누락과 대조했다. 재현 도구에는 현재 실행의 JUnit/application.log/warnings.json 자동 보존을 추가했다. 이 로그 보존 보완은 다음 실행부터 적용되는 것으로, 본 측정은 원본 JUnit을 별도 복사한 뒤 추출했다. smoke는 별도 JSON/run.log가 남아 있으나 JUnit은 본 실행 전 복사하지 못했다. 본 측정을 반복해 유리한 결과를 선택하지 않았다.

### smoke 검산

baseline→modified 각각 1작업자·1매장·10웨이팅, 워밍업0/표본4/반복1로 실행했다. 각각 HTTP 200 4건, 응답 JSON이 일치했다. 최종 CALLED/call_count2/recall_count1/responded_at null, outbox PENDING 3건/attempt0이었다. baseline 이벤트 0건, modified 이벤트 59건(업무4 + outbox3 + SSE52)이며 직접 INSERT rows56 + 트리거 INSERT rows3 =59로 실제 행 수와 일치했다. smoke의 최초 호출과 뒤의 새 호출·수락·기한 유지 재전송이 구분됐다.

### 본 측정 조건과 지연

고정 Clock `2031-01-20T03:00:00Z`/Asia/Seoul, Java Temurin 21.0.12.1, test JVM `-Xmx512m`, 최대 DB pool12, PostgreSQL16.15/동일 이미지·DB 설정이다. JVM 인자 중 snapshot별 임시 작업 경로만 다르다. baseline 실행 03:54:15.217–03:54:27.578 KST, modified 03:55:14.928–03:55:29.005 KST로 겹치지 않는다. 4작업자·4매장·40웨이팅, 작업자당 warmup20/표본200/3반복, call→accept→recall→call의 closed-loop다. 각 반복 표본800, 전체 표본2,400이며 워밍업240은 지연 집계에서 제외했다. 등록은 SQL fixture이며 접수 API 비용은 포함하지 않았다.

| 조건 | 표본 | 오류 | p50 ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline 반복1 | 800 | 0 | 7.277 | 11.538 | 20.191 |
| modified 반복1 | 800 | 0 | 7.532 | 12.656 | 14.219 |
| baseline 반복2 | 800 | 0 | 5.020 | 7.605 | 8.650 |
| modified 반복2 | 800 | 0 | 5.168 | 9.904 | 13.283 |
| baseline 반복3 | 800 | 0 | 4.702 | 7.195 | 8.235 |
| modified 반복3 | 800 | 0 | 4.808 | 9.439 | 10.807 |
| baseline 전체 | 2,400 | 0 | 6.129 | 10.417 | 12.760 |
| modified 전체 | 2,400 | 0 | 7.181 | 11.986 | 13.968 |
| 전체 변화 | — | — | +17.17% | +15.07% | +9.47% |

전체는 2,400개 원시 표본을 합친 nearest-rank percentile이며 반복별 percentile의 평균이 아니다. warmup도 양쪽 각각240건 HTTP 오류0이다. 각 본 실행 JUnit harness 1개가 실패/오류/skip0으로 끝났으며 이는 기존 286개 회귀를 다시 실행했다는 뜻이 아니다. HTTP 요청 phase 합계 3.291→3.948초, 그 구간의 closed-loop 처리량 729.16→607.89 requests/s(-16.63%)다. 끝에 남은 기록 drain은 이 처리량과 HTTP percentile에서 제외하고 아래에 별도로 표시한다.

### 실제 이력, 누락, SQL

**제품 한계/후속 판단 필요:** 단일 writer·512개 관찰 큐가 본 부하에서 반복마다 포화됐다. 표본 구간 예상 SSE 31,200건 중 5,101건만 저장되고 26,099건(83.6506%)이 누락됐다. warmup 포함 큐 거부 경고27,251건은 기대행-실제행 차이와 정확히 일치하며 DB 기록 실패 경고는0건이다. drain 성공은 수락된 작업이 끝났다는 뜻이고 거부된 관찰을 복구하지 않는다. START/FINISH 개수도 다르므로 완전한 전달 근거를 재구성할 수 없다. 요청 성공/낮은 지연만으로 통합 이력의 기록 품질이 충분하다고 판단할 수 없다. 관리자에게 이 결과를 보고하며 제품 변경/수용 판단은 남긴다.

| modified 반복 | 표본 업무/outbox 실제(기대) | 표본 SSE 실제(기대) | 표본 총 이벤트 | warmup 포함 실제 총 행 | 종료 drain ms | 이벤트 총 용량 bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 1 | 800/600 (800/600) | 1,801 (10,400) | 3,201 | 4,041 | 220.675 | 3,383,296 |
| 2 | 800/600 (800/600) | 1,666 (10,400) | 3,066 | 3,842 | 207.222 | 3,260,416 |
| 3 | 800/600 (800/600) | 1,634 (10,400) | 3,034 | 3,806 | 214.067 | 3,244,032 |

표본 업무 사건2,400/2,400과 outbox 상태 사건1,800/1,800은 모두 저장됐다. 표본 총 이벤트9,301건 = 직접 이벤트 INSERT rows7,501 + 트리거 INSERT rows1,800이다. 반복별 warmup 포함 기대 총 행12,980건에 비해 실제4,041/3,842/3,806건으로 누락8,939/9,138/9,174건이다. 이벤트 용량은 인덱스·TOAST·페이지 할당을 포함한 pg_total_relation_size이며 반복마다 TRUNCATE해 세 용량을 합산하지 않는다. warmup 이후 용량 증가분은 각각2,637,824/2,547,712/2,539,520 bytes다. 기록 누락이 있는 이 크기를 유실 없는 저장 비용으로 환산하지 않는다.

양쪽 반복별 outbox는 warmup 포함660건 전부 PENDING/attempt0, 최종 각 대상은 CALLED/call_count110/recall_count55/responded_at null이다. 보존된 modified 마지막 데이터의 모든 관찰 customerReceipt는 UNKNOWN이고 SSE connections는0이다. 공급사 실행/고객 전달 성공을 관찰한 결과가 아니다.

| 표본 구간 SQL calls | baseline 1/2/3 | modified 1/2/3 | 전체 baseline→modified |
| --- | --- | --- | --- |
| 직접 업무 SQL | 8,600 / 8,600 / 8,600 | 11,201 / 11,066 / 11,034 | 25,800→33,301 (+29.07%) |
| 내부 FK 검사 | 1,200 / 1,200 / 1,200 | 4,401 / 4,266 / 4,234 | 3,600→12,901 |
| V36 이벤트 trigger INSERT | 0 / 0 / 0 | 600 / 600 / 600 | 0→1,800 |
| 감시·통계 초기화 SQL | 29 / 21 / 20 | 37 / 30 / 29 | 70→96 |
| 트랜잭션 제어 등 | 5 / 0 / 0 | 20 / 7 / 0 | 5→27 |

직접 SQL total_exec_time 합은254.891→1,353.975ms, 직접 SQL WAL은1,414,439→11,180,115 bytes다. 내부 FK/trigger의 시간·WAL은 별도로 comparison.json에 보존했으며 상위 실행 시간/WAL에 포함될 수 있으므로 더해서 총 비용으로 계산하지 않았다. pg_stat_statements의 내부 호출 횟수는 JDBC round trip이 아니다. 쿼리별 calls/rows/buffer/WAL 원본을 보존했고 감시 쿼리를 업무 SQL에서 분리했다. 경고 로그 출력 비용과 관찰 손실도 현재 구현의 측정에 포함된다.

### 자원과 해석 한계

JVM 프로세스 CPU(요청+drain)는 총9,119→12,110ms(+32.80%)였다. 표본 자원 스냅샷은 baseline67개/modified93개, 50ms 간격이다. 최대 연결 사용8→9, 연결 대기 최대0→0, DB lock waiter 최대0→1, SSE 큐 최대0→512다. 관측 heap 최대170,924,544→119,944,328 bytes는 GC 타이밍이 달라 메모리 개선의 근거가 아니다. 18논리 CPU 호스트의 구간 load average는 baseline5.47–5.83 / modified5.55–6.47, host CPU 표본 평균14.52% /14.90%였다. 원시 free-memory 카운터는 macOS available-memory를 의미하지 않는다.

독점 슬롯은 다른 개선 작업의 부하 동시 실행을 피한 것이며 OS 전체를 독점했다는 뜻이 아니다. 작은 표본/짧은3반복·JIT/캐시·항상 baseline 우선 순서·50ms 샘플링 때문에 인과/임계점/운영 용량을 일반화할 수 없다. SSE 연결0/simulation이며 브라우저 표시/고객 ACK/실제 SOLAPI·결제 PG 성능은 미검증이다. 포화 임계점을 찾기 위해 추가 부하를 수행하지 않았다. **측정 오버헤드는 관찰 누락 상태의 수치**다.

### 원본, 변경 지문, 종료 자원

원본 묶음: `build/waiting-event-history/stage2-1794336488613412000`. 본 측정 경로는 다음과 같다.

- baseline: `build/waiting-event-history/benchmarks/baseline-1794336689741071000/results/main-1794336854995612000`
- modified: `build/waiting-event-history/benchmarks/modified-1794336742305272000/results/main-1794336914709671000`
- 같은 snapshot의 `results/smoke-1794336752311730000`(baseline), `results/smoke-1794336791998660000`(modified)가 별도 smoke 증거다.
- 원본 묶음의 `comparison.json`, `analysis-final.log`, `smoke-verification.json`, `main-modified-preflight-timewait.log`, `source-verification.json`, `preservation-check.json`, `final-resources.json`에 검산·실패·보존 결과가 있다.
- `input-before/`, `tools-main-executed/`, `tools-executed-sha256.json`, `tools-main-executed-sha256.json`, `tools-final-sha256.json`, `tool-changes.diff`에 입력/실제 실행/최종 도구 변경 지문을 보존한다. 포트 보완 전 runner는 당시 SHA256과 정확히 일치하는 `runner-before-preflight-fix.py`로 재구성해 보존했다. `preserved-before.sql`, `preserved-after.sql`, `bench-final.sql`로 기존 데이터와 마지막 부하 데이터를 보존했다.

최종 도구 파일 map의 SHA256은 `94a3660102e90bb845cdadd5d166ed64aade70369fc8ac08959f49ebdca877e7`이다.

기준/current HEAD: `a44952e37993e5003c860714f126057cd311dba5`. 정렬한 경로→SHA256 JSON의 SHA256은 다음과 같다.

| 지문 | SHA256 |
| --- | --- |
| 변경 없는 C src/main+src/test 415파일 | `d0f8355062ef87ca2532388abadb6b2b368435f2a11384b6e10bac8bb4ddfde8` |
| baseline snapshot 입력 map | `92570be119d98390c7cf07819f8adefacf476077dbbad0dbdc6a92affe9817a2` |
| modified snapshot 입력 map | `703006543e9a9c870b0e9dd0b749869b3ba3b2903ada1f2f818ce0e0852e0e0b` |
| 양쪽 동일 Java harness 파일 | `64454c0b4f0ccb4f17f5a213007395fcf20e3c4ef0efed94eb95dac8f2fd7a87` |

기존 public/baseline_c의 COPY 데이터·시퀀스70개 항목은 전후 동일하다(행 순서만 정규화). 허가된 pg_stat_statements 확장은 public에 추가됐으므로 스키마 바이트 전체가 동일하다는 뜻은 아니다. 두 snapshot 입력은 종료 시 다시 manifest와 대조했다. 도구 Python 구문·소켓 guard·검산기·git diff --check를 확인했다. 이번 변경 범위는 `scripts/benchmarks/waiting-history/{prepare.py,run.py,local_runtime.py,WaitingHistoryBenchmark.java,archive_logs.py,analyze.py,README.md}`, 이 work-log 및 C 보고서이며 제품/기존 테스트 수정은0이다.

마지막 검사에서 앱18083/fake19083 listener 없음, C worktree Java/Gradle 실행 없음, benchmark 잠금 해제, C DB의 다른 세션 없음이었다. 전용 PostgreSQL 컨테이너는 실행 상태로 보존하고 bench_c 마지막 반복 데이터와 public/baseline_c를 유지했다. 다른 컨테이너/DB는 건드리지 않았다. 남은 일은 관찰 누락의 제품 수용/보완 판단과 사용자 의사 감사·게시 승인 등 관리자 후속이다. 워크트리·브랜치·증거는 삭제하지 않는다.

## SSE 관찰 batch 보완 준비 — 실행 슬롯 대기 (2026-11-11)

관리자는 83.65% 관찰 누락을 목표 미충족으로 판단하고 저장 비용 보완을 지시했다. 이 단계는 원인 검산·제품/테스트/도구 수정·정적 확인까지만 수행했다. **Java 컴파일, Gradle, 앱, DB 접속/설정, 부하를 실행하지 않았다.** I의 기존1,075개 성공 및 앞선 C286개 성공은 아래 수정본의 검증 결과가 아니다. 보완 수용 여부를 묻지 않고 지정된 범위의 구현 준비를 완료했다.

### 31,200행 기대값 재검산

`OwnerWaitingService.callWaiting`은 고객 호출1 + 점주 목록1 + `WaitingQueueNotifier`의 활성 웨이팅 순번10으로 호출당12번 알림을 전송한다. CALLED도 WaitingStatus.ACTIVE에 포함되며 이 fixture는 수신 설정을 끄지 않는다. `respondToCall`은 새 호출의 첫 수락 때 점주1번, `/recall`은 고객1번이며 순번 브로드캐스트를 하지 않는다. `NotificationService.send`는 연결0개여도 START/FINISH를 각각 남긴다.

따라서 call→accept→recall→call은 `(12+1+1+12)×2=52` SSE 관찰이다. 표본은 `4작업자×200요청÷4×3반복=600주기`, 기대 `600×52=31,200행`; warmup 포함34,320행이다. 기존 smoke도 SSE52+업무4+outbox3=59행이었다. 원래 표본 저장5,101/누락26,099, warmup 포함 누락27,251과 큐 거부 경고27,251이 일치한다. 기대값을 줄이거나 0연결/같은 사건 관찰을 제외하지 않았다.

직접 확인한 누락 원인은 생산 속도가 단일 writer의 처리 속도를 넘어 512개 큐에서 AbortPolicy가 거부한 것이다. 기존 코드는 관찰1행마다 연결을 얻어 별도 트랜잭션과 INSERT를 실행했다. 이번 보완은 그 저장 비용을 줄이는 것이며 실제 처리량 개선/누락 해소는 아직 측정하지 않았다.

### 준비한 최소 변경

- `WaitingSseObservationRecorder`: 기존 단일 ThreadPoolExecutor와 queue512/AbortPolicy를 유지한다. 실행할 관찰1개를 꺼낸 worker가 이미 대기 중인 관찰만 FIFO로 최대64개까지 즉시 모으고 한 트랜잭션으로 저장한다. 묶음이 찰 때까지 기다리지 않는다. 호출자에게 DB 작업을 넘기거나 DB 연결/공유풀을 기다리게 하지 않는다. 큐512 + 처리 중 최대64로 메모리 상한을 유지한다.
- `WaitingEventRepository.appendBatch`: 기존과 같은 INSERT/ON CONFLICT(event_key) DO NOTHING을 JdbcTemplate.batchUpdate로 실행한다. nullable call/recall 등의 JDBC 타입을 지정해 행마다 parameter metadata 조회가 발생하지 않게 한다. 기존 업무 `append`의 SQL/트랜잭션 계약은 유지하며 새 batch 메서드도 MANDATORY다. 마이그레이션은 추가/변경하지 않았다.
- event_key, 최초 관찰 시각, 호출/재전송 차수, source ID, details, START/FINISH를 합치거나 다시 만들지 않는다. future는 묶음 commit 반환 뒤 완료된다. 묶음 실패 때 실패한 트랜잭션 밖에서 각 관찰을 새 트랜잭션으로 재시도하므로 잘못된1행 때문에 정상 이웃 행을 조용히 잃지 않는다. commit 결과가 불명이어도 같은 event_key로 재시도해 중복을 방지한다. 개별 실패는 기존 경고와 exceptional future로 남긴다.
- graceful stop은 기존5초 제한 안에서 drain한다. timeout/interruption이면 큐뿐 아니라 처리 중 batch의 미완료 future도 저장 결과 미확인으로 끝내고 event_key별 로그를 남긴다. 중단된 JDBC가 나중에 commit할 수 있으므로 이 예외를 확정 미저장으로 주장하지 않는다.
- 전송은 기존 동기 AFTER_COMMIT이며 NotificationService/Listener/QueueNotifier/Waiting 서비스와 API·SSE payload·customerReceipt UNKNOWN을 바꾸지 않았다. 새 전달 큐·CallerRuns·Redis/Kafka·무한 큐·관찰 생략을 추가하지 않았다.

배치가 여러 waiting FK를 같은 트랜잭션에서 확인하므로 잠금 유지 범위가 늘 수 있다. 다음 실제 동시성/작은 pool 회귀에서 이 영향을 확인해야 한다. DB 장애에서는 batch rollback 뒤 개별 시도가 추가되므로 부하가 더 들 수 있지만 각 future와 로그를 남기며 장애를 조용히 성공으로 처리하지 않는다. 무한 장애·프로세스 강제 종료에 대한 durable queue 보장도 추가한 것이 아니다.

### 새 테스트와 도구 (아직 실행하지 않음)

새 단위 테스트6개를 `WaitingSseObservationRecorderTest`에 준비했다: 130관찰의64/64/2 분할과 commit 전 미완료·metadata 보존, 단일 불량 행의 분리 재시도/future, 불명 commit 후 동일 event_key, 512개 실제 관찰 포화와 호출자 DB 작업 없음, 정상 종료 drain, 처리 중/대기 중 종료 미확인 future와 늦은 commit이다. 기존 실제 DB `WaitingEventHistoryTest`에는 정상10웨이팅 fan-out52행과 CHECK 실패·중복키가 섞인 batch의 rollback/정상 이웃 보존2개를 추가했다. 기존 실패/포화/API/원자성 테스트 기대값을 약화하지 않았다.

기존 Java benchmark harness와 파라미터는 그대로다. `archive_logs.py`는 batch fallback 및 종료 미확인 경고를 추가로 센다. `analyze.py --require-complete-observations`는 표본/워밍업의 START·FINISH 각각 기대 수, 전체 행 수와 큐 거부/쓰기 실패/종료 미확인을 검사하고 누락이 있으면 비교 파일을 보존한 뒤 실패로 끝난다. 이를 원래 손실 자료에만 읽기 전용으로 적용해 실패 판정을 확인했다. 이것은 새 구현의 실행 검증이 아니다.

### 정적 확인과 다음 슬롯 계획

`git diff --check`, 변경 파일 공백 검사, Python AST, 원래 원시 증거89개 SHA256 보존, 원래 손실의 엄격 완전성 판정 실패를 확인했다. 제품 변경은 recorder와 event repository2파일, 테스트는2파일(새 메서드8개), 도구는2파일과 문서다. Java 소스/타입/드라이버 batch 동작은 아직 compile 또는 runtime 검증되지 않았다.

I 종료 뒤 배정되는 C 슬롯에서 다음을 순차 실행한다.

1. 캐시 Java21/Gradle --offline으로 새 단위/DB batch 실패 테스트부터 실행한다. 정상 commit·부분 제약 실패 rollback·중복키·0연결52행·포화·종료 future를 확인한다.
2. 기존 waiting/notification 관련 전체 회귀와 작은 연결 풀의 동시 접수/연기/호출을 실행해 업무/이력 원자성과 기존 전달이 유지되는지 확인한다. public/baseline_c 및 기존 증거 보존을 위해 C DB 내 분리 스키마를 사용한다.
3. fresh baseline/modified snapshot으로 동일 smoke(1작업자/10웨이팅/0warmup/4요청/1반복)를 순차 실행해 59행·응답·SQL rows를 검산한다.
4. 같은 본 조건(4작업자/4매장/40웨이팅, 작업자당20warmup/200표본,3반복, 같은 Clock/JVM/DB/순서)으로 baseline→modified를 순차 실행한다. 기존 실행 결과는 덮어쓰지 않는다.
5. `--require-complete-observations`로 표본 SSE31,200 및 업무2,400/outbox1,800, warmup 포함 총38,940 이벤트의 완전성을 확인한다. HTTP/SQL rows/큐 drain/경고/행별 phase와 반복별·전체 p50/p95/p99·자원·용량을 함께 보고한다. 원래 손실 상태 오버헤드와 새 결과를 분리하고, 완전성 통과 전에는 유실 없는 저장 비용이라고 부르지 않는다.

준비 증거 경로: `build/waiting-event-history/batch-preparation-1794337923439250000`. `before/`, `before-sha256.json`, `prepared-source-sha256.json`, `prepared-changes.diff`, `fanout-audit.json`, `old-loss-gate.log`, `old-loss-rechecked.json`, `static-checks.json`을 보존한다. 준비된 제품 src/main 파일 map SHA256은 `30061196b2c0985cefb37c124c0014b25da0fc0fe42223d5518d6ee9d1f12462`이다. 실제 실행 프로세스를 시작하지 않았고 전용 컨테이너·포트·DB 상태를 변경하지 않았다. 다음 C 실행 슬롯 배정과 그때의 실제 검증이 남아 있다.

## batch 보완 실제 검증 완료 — 2026-11-11 (I 재통합 필요)

배정된 C 독점 슬롯에서 준비된 보완을 실제로 검증했다. 관련 **28클래스294개 테스트와 bootJar가 성공**했고, 같은 본 조건의 표본 SSE31,200행/전체 이벤트35,400행 및 warmup 포함38,940행이 모두 저장됐다. 큐 거부·쓰기 실패·batch 재시도·종료 미확인은0건이었다. 기존 손실 결과를 덮어쓰지 않았으며 아래 수치는 **이 실행 조건에서 관찰 누락이 없는 저장 비용**이다. 무제한 부하/프로세스 강제 종료에서 영구 보존을 보장한 것은 아니다.

### 실제 회귀와 범위

- 집중 실행: recorder 새 단위6개 + DB 이력20개 =26개, 실패/오류/skip0.
- 관련 전체 실행: waiting/notification28클래스294개, 실패/오류/skip0; bootJar 성공. 집중26개는294개에 포함되므로 합산하지 않는다.
- 실제 batch CHECK 제약 실패 시 전체 rollback 뒤 정상 이웃 행 저장·중복키1행 유지·실패 future, 불명 commit 뒤 같은 key 재시도, commit 전 future 미완료, 정상/중단 종료, queue512 포화에서 호출자 DB 처리 없음과 SSE 전송 지속이 통과했다.
- 기존 작은 pool의 동시 접수/연기/호출·응답, 같은 TX 상태/이력/outbox rollback, 기존 HTTP/SSE payload·권한·수신 설정, 알림톡 모의 HTTP/최종 보고 경계, V35→V36 원본 보존 회귀가 포함됐다.
- Java21/기존 Gradle9.7.1 캐시를 직접 `--offline --no-daemon --max-workers=2 -Dorg.gradle.java.installations.auto-download=false`로 실행했다. C DB의 `regression_c_batch`만 회귀 기본 스키마로 사용하고 public/baseline_c는 보존했다. 최종 회귀 명령은 `regression-1/execution.json`, 원본 XML/HTML/run.log는 그 폴더에 있다.
- 제품/테스트는 준비본 그대로 실행했으며 실행 중 수정하지 않았다. 제품 변경2파일(recorder/repository)·테스트2파일(단위6 + DB2 추가)의 상세 전후 지문은 아래에 있다. 정책/API/SSE 전달 구조·V36 SQL·공통 build.gradle/application.yml은 이번 보완에서 변경하지 않았다.

### 같은 조건의 smoke 및 본 측정

동일 새 harness로 baseline/modified를 각각 compile하고, 1작업자·10웨이팅·warmup0·4요청·1반복 smoke를 순차 실행했다. 양쪽 응답 JSON이 동일하고 오류0, 변경본은 업무4/outbox3/SSE52=59행·START/FINISH26개씩·SQL INSERT rows59가 일치했다. 관찰 저장 commit은16개이며 batch/쓰기/종료 경고0이었다. smoke의 cold-start 지연은 본 성능 수치에 합산하지 않았다.

본 조건은 기존과 동일한 4작업자·4매장·40웨이팅, 작업자당20warmup/200표본/3반복, call→accept→recall→call, 고정 Clock `2031-01-20T03:00:00Z`/Asia/Seoul, Java21.0.12.1/test JVM Xmx512m/pool12/PostgreSQL16.15/pg_stat_statements.track=all이다. fixture와 API 기대값·SSE0연결/simulation은 그대로다. baseline04:25:31.283–04:25:42.945, modified04:25:43.262–04:25:55.785 KST로 겹치지 않는다.

트랜잭션 수 요청에 맞춰 **외부 harness에만** 동일한 Spring TransactionExecutionListener 계측을 양쪽에 추가했다. 새 physical Spring transaction의 commit/rollback/failure와 관찰 writer thread commit을 센다. JDBC autocommit·통계조회·Flyway·fixture·warmup은 표본 transaction 집계 대상이 아니다. DB 쿼리를 추가하지 않는 메모리 카운터이며 원래 손실 실행에는 이 계측이 없었으므로 두 시점의 절대 지연을 직접적인 개선율로 해석하지 않는다.

| 조건 | 표본 | 오류 | p50 ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline 반복1 | 800 | 0 | 5.883 | 10.043 | 11.347 |
| batch 반복1 | 800 | 0 | 7.344 | 11.425 | 13.234 |
| baseline 반복2 | 800 | 0 | 4.388 | 7.368 | 8.269 |
| batch 반복2 | 800 | 0 | 6.940 | 8.958 | 10.596 |
| baseline 반복3 | 800 | 0 | 5.783 | 7.104 | 8.265 |
| batch 반복3 | 800 | 0 | 6.457 | 8.828 | 10.552 |
| baseline 전체 | 2,400 | 0 | 5.859 | 9.326 | 10.557 |
| batch 전체 | 2,400 | 0 | 6.607 | 10.478 | 11.878 |
| 전체 변화 | — | — | +12.75% | +12.35% | +12.52% |

전체 percentile은 원시2,400표본의 nearest-rank이며 반복별 percentile 평균이 아니다. 양쪽 warmup240건씩도 오류0이다. 요청 phase 합은3.113→3.618초, closed-loop 처리량771.07→663.36 requests/s(-13.97%)이며 종료 drain은 아래에 분리한다. 추가 검증 목적 없이 본 측정을 반복하거나 가장 좋은 반복을 고르지 않았다.

### 완전성·트랜잭션·용량

| batch 반복 | 표본 업무/outbox/SSE 실제=기대 | 표본 START/FINISH | warmup 포함 총 실제=기대 | 관찰 commit 수 | 종료 drain ms | 관측 queue 최대 | 이벤트 총 bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 800 /600 /10,400 | 5,200 /5,200 | 12,980 | 813 | 13.088 | 26 | 9,920,512 |
| 2 | 800 /600 /10,400 | 5,200 /5,200 | 12,980 | 819 | 12.320 | 13 | 9,838,592 |
| 3 | 800 /600 /10,400 | 5,200 /5,200 | 12,980 | 794 | 13.955 | 36 | 9,863,168 |

표본 합은 업무2,400 + outbox1,800 + SSE31,200 =35,400행이다. warmup 포함은 업무2,640 + outbox1,980 + SSE34,320 =38,940행이며 각 반복 START/FINISH는5,720개씩이다. 큐 거부/쓰기 실패/batch fallback/종료 미확인 경고가 모두0이고 `--require-complete-observations`가 통과했다. 직접 이벤트 INSERT rows33,600 + V36 trigger rows1,800 =35,400으로 SQL과 실제 증가행 수도 일치한다.

baseline의 Spring commit은 반복마다1,200개(전체3,600), 수정본은2,013/2,019/1,994개(전체6,026)다. 차이2,426개는 관찰 저장 commit이며 31,200관찰을 평균12.86행/commit으로 저장했다. 최대64개 제한은 유지했고 임의로64개를 채우기 위해 기다리지 않아 평균 크기는64보다 작다. 양쪽 rollback/실패 callback0이다. 기존 손실 구현은 소스상 관찰1행당1트랜잭션이었고, 당시 listener 실측값은 없으므로 과거 tx 수를 측정값으로 꾸미지 않는다.

이벤트 용량은 table/index/TOAST 포함 실제 pg_total_relation_size이며 warmup 이후 증가분은8,904,704/8,806,400/8,822,784 bytes다. 각 반복에서 TRUNCATE하므로 최종 DB에는 마지막12,980행을 보존하고 용량을 세 배로 합산하지 않는다. baseline에는 이벤트 테이블이 없다. 기존 손실 실행의 약3.1MiB와 이번 약9.4MiB는 저장행 수가 달라 저장 효율 개선율로 비교하지 않는다.

양쪽 반복 최종 대상은 CALLED/call_count110/recall_count55/responded_at null이며 outbox는 각각660행 전부 PENDING/attempt0이다. 마지막 batch 데이터의 SSE11,440행은 연결0·customerReceipt UNKNOWN이며 공급사 실행/고객 수신 성공으로 확대하지 않는다.

### SQL·자원·기존 손실 결과와 구분

| 표본 SQL calls | baseline 반복1/2/3 | batch 반복1/2/3 | 전체 baseline→batch |
| --- | --- | --- | --- |
| 직접 업무 SQL | 8,600 /8,600 /8,600 | 19,800 /19,800 /19,800 | 25,800→59,400 |
| 내부 FK 확인 | 1,200 /1,200 /1,200 | 13,000 /13,000 /13,000 | 3,600→39,000 |
| V36 이벤트 trigger INSERT | 0 /0 /0 | 600 /600 /600 | 0→1,800 |
| 감시·통계 초기화 | 26 /21 /20 | 30 /24 /24 | 67→78 |
| SQL 트랜잭션 제어 등 | 11 /0 /0 | 11 /11 /0 | 11→22 |

pg_stat_statements는 JDBC batch의 각 행 INSERT도 개별 실행으로 센다. 따라서 완전한 기록의 SQL 횟수는 증가하지만 저장 트랜잭션/JDBC batch 수와는 다른 지표다. 직접 SQL total_exec_time은243.565→1,305.489ms, 직접 SQL WAL은1,412,235→35,717,098 bytes다. 내부 FK/trigger 시간·WAL을 상위문에 더해 이중 합산하지 않았다. 쿼리별 calls/rows/시간/버퍼/WAL은 sql-N.json에 남겼다.

JVM CPU(요청+drain)는8,398→9,619ms(+14.54%)다. 50ms 자원 표본64→75개에서 pool 사용 최대8→9, pool 대기0→0, lock waiter0→3, heap 최대118,903,480→170,499,528 bytes였다. batch가 여러 FK 확인을 같은 트랜잭션에서 유지하는 영향 가능성이 있으며 회귀/본 측정은 오류 없이 끝났지만 잠금 대기 비용이 없어졌다고 주장하지 않는다. queue의 표본 최대36은 50ms 샘플링 값이지 모든 순간의 정확한 최대값이 아니다. 18논리 CPU 호스트의 load average 범위는 baseline4.96–5.04 /batch5.60–6.03, host CPU 표본 평균13.48% /13.93%다.

원래 손실 결과는 SSE5,101/31,200 저장(83.65% 누락), p95 10.417→11.986ms(+15.07%)였다. 이번은31,200/31,200 저장, p95 9.326→10.478ms(+12.35%)다. 과거 지연은 버린 기록을 저장하는 비용을 포함하지 않은 값이고, 이번 paired 결과는 해당 조건에서 모두 저장한 비용이다. 서로 다른 시점의 호스트 부하/JIT/캐시와 새 transaction counter 차이를 제거한 실험은 아니므로 두 오버헤드의 차이를 보편적 성능 개선율로 주장하지 않는다.

운영 용량·포화 임계점·실제 SSE 연결/브라우저 표시·고객 ACK·실제 SOLAPI/결제 PG 성능은 검증하지 않았다. 유한 queue512는 계속 유지되며 더 큰 부하/DB 장애에서는 명시적 거부·실패가 가능하다. 이것은 기존 전송을 막지 않는 관찰 저장 정책이며, 정상 포화/DB 장애/종료 미확인 동작을 테스트했다.

### 산출물·지문·종료 자원

현재 검증 원본: `build/waiting-event-history/batch-validation-1794338520607415000`.

- `focused-1/`, `regression-1/`: 실제 명령·run.log·원본 XML, 전체 회귀 HTML; `regression-counts.json`에26/294개 집계.
- smoke: `build/waiting-event-history/benchmarks/baseline-1794338662111664000/results/batch-smoke-1794338691121086000` 및 `build/waiting-event-history/benchmarks/modified-1794338666463115000/results/batch-smoke-1794338699422833000`.
- 본 baseline: `build/waiting-event-history/benchmarks/baseline-1794338662111664000/results/batch-main-1794338731068518000`.
- 본 modified: `build/waiting-event-history/benchmarks/modified-1794338666463115000/results/batch-main-1794338743044269000`.
- 각 결과에 environment/jvm/requests/warmup/resources/sql/state-before/state-after/summary/JUnit/application.log/warnings를 보존했고 `comparison.json`, `smoke-comparison.json`, `main-analysis.log`에서 검산한다.
- `changes-before-after.json`, `source-verification.json`, `input-sha256.json`, `tools-final-sha256.json`, `evidence-sha256.json`, `final-resources.json`, `preservation-check.json`, `bench-final.sql`에 전후 지문·보존/종료 상태를 기록한다. 원래 손실 자료89개와 이전 XML/로그는 그대로다.

| 제품 변경 파일 | 보완 전 SHA256 | 검증된 보완 후 SHA256 |
| --- | --- | --- |
| WaitingSseObservationRecorder.java | `845fdb2feec698cb8a29a505c1ae8e233fe48a09e8dbc523a968eef4528aebb1` | `b0114a23e2ecdf374d5c72474b41667c6d4f22f5dae8670cd7c19207c9df52e7` |
| WaitingEventRepository.java | `16afaf0664d8eb5cfcc9491dcb20de1fdeaf55aeab6f21cef23fd758681f1baa` | `e153ca8d1f91b049af34324a8bf37e803381899dd295c5822424736b3250c485` |

제품 src/main map SHA256은 보완 전 `43237e0ac0c33ccc7a7ed6fc85ff9f1e8d9a7d007bafa308393801c2acb94a34` → 검증본 `30061196b2c0985cefb37c124c0014b25da0fc0fe42223d5518d6ee9d1f12462`이며 제품+테스트416파일 map은 `a151765cc00ff263059e5b46087dfd90fec8dc187750e94c39a1fe3bde250352`다. 기준 HEAD는 `a44952e37993e5003c860714f126057cd311dba5`이고 커밋하지 않았다. baseline/modified snapshot 입력 map은 각각 `f48761504294621f7a8050e75e212bce85396fac7e1ca857cdef71267490e1af` / `4720bf7da2ae14d9a17d283532a3ebf10d911144d7b9f163fbc6603a096d503b`, 동일 Java harness는 `9755f7330f2d2662c4f98e65049be00be832f5dd8ce848dd979e80e70f533077`다.

이번 보완의 정확한 변경 범위: 제품2파일 위 표, `WaitingSseObservationRecorderTest.java`(신규), `WaitingEventHistoryTest.java`(DB2개 추가), 도구 `WaitingHistoryBenchmark.java`(transaction counters), `analyze.py`(완전성/transaction 출력), `archive_logs.py`(경고 집계), 도구 README·이 work-log·C 보고서다. 개별 파일 전후 hash는 changes-before-after.json에 있다. 앞선1차 기능 구현 파일을 이번 batch 보완 변경이라고 다시 세지 않는다.

기존 public/baseline_c COPY 데이터·시퀀스70항목은 전후 동일했다. 종료 검사에서 앱18083/fake19083 listener 없음, C Java/Gradle 프로세스 없음, run lock 해제, C DB 다른 세션0이었다. 전용 ct-improve-c-20261111/55433/ct_improve_c는 실행 상태로 유지하고 public/baseline_c·regression_c_batch·마지막 bench_c 데이터와 dump를 보존했다. I 원본/DB에는 적용하거나 접근하지 않았다.

**남은 범위는 제품2파일과 테스트/도구의 I 재통합 및 공통 검증**이다. 이전 I1,075개 성공을 이 보완본의 성공으로 대체하지 않는다. 사용자 의사 감사/커밋·게시 승인, 실제 공급사/고객 수신·배포·CI도 별도다. C에 배정된 보완·관련 회귀·동일 조건 재측정은 완료했으며 추가 부하를 실행하지 않는다.

## C-BATCH-SPEC-1 도구 실패 기록 보완 완료 — 2026-11-11

두 batch 리뷰에서 제품 결함은 새로 확인되지 않았고 P3 검산 도구의 실패 기록만 보완했다. 기존 `describe()`의 `writeFailed>0`/큐 거부 수 불일치 assertion이 비교 JSON 저장 전에 종료하는 현상을 보존된 smoke 복사본에서 재현했다. 이 작업은 **작은 파일 기반 Python 검증만** 수행했으며 Java/Gradle/앱/fake PG/DB 접속·새 부하를 실행하지 않았다. 아래 합성 입력 오류는 제품 장애 재현이 아니다.

`analyze.py`의 성공 JSON 형식·수치 계산·엄격한 검사 조건은 유지했다. 입력/검산 예외에는 최상위 `status=FAILED`, 예외 종류·사유·입력·단계·위치를 기록하고 종료1을 반환한다. 실패 전 계산은 `partial_comparison` 안에만 있어 성공과 구분된다. 엄격 완전성 실패도 같은 실패 구조 안에 기존 비교 전체와 observations_complete=false를 보존한다. 같은 디렉터리 임시 파일을 원자 교체해 이전 성공 JSON이 새 실패 뒤 남아 오인되는 문제를 막았다.

출력 경로가 쓰기/교체 불가능하면 JSON 보장은 하지 않으며 종료2와 stderr로 기존 파일을 이번 결과로 사용하면 안 된다고 알린다. 인자 파싱 오류도 결과 저장 보장 밖이다. README에 이 경계를 적었다. 실패를 삼키거나 검사 기대값을 낮추지 않았다.

### 파일 기반 검증 결과

`verify_analyzer_failures.py`가9개 시나리오에서 분석기를 총13회 실행해 예상 종료/JSON을 확인하고 자체 종료0으로 끝났다. 분석기 종료 분포는 정상0 두 번, 예상 검산 실패1 열 번, 출력 경로 실패2 한 번이다.

| 시나리오 | 수정 전 | 수정 후 | 새 증거 |
| --- | --- | --- | --- |
| 정상 smoke 복사본 | 0, 성공 JSON | 0, **JSON 바이트 동일** | normal-before/normal-after.json |
| 합성 writeFailed=1 | 1, 출력 JSON 없음 | 1, FAILED·원인·modified 입력/단계 | write-failed-after.json |
| 합성 queueRejected=1/기대누락0 | 1, 출력 JSON 없음 | 1, FAILED·실제/기대 불일치 | queue-mismatch-after.json |
| 초기 environment.state=RUNNING | — | 1, FAILED·baseline 입력/단계 | early-input.json |
| 잘못된 JSON / 누락 입력 파일 | — | 각각1, FAILED 진단 | malformed-json.json / missing-input.json |
| 이전 성공 출력 경로 재사용 후 실패 | — | 1, 이전 성공 대신 FAILED로 교체 | reused-output.json |
| 기존83.65% 손실 자료 엄격 모드 | 1, 기존 비교 JSON | 1, 실패 진단·partial_comparison이 기존 비교와 동일 | loss-before/loss-after.json |
| 출력 경로가 디렉터리 | — | 2, stderr·임시 파일 정리, JSON 미보장 | unwritable-output.stderr.log |

정상 수치/완전성과 기존 손실의 실패 판정이 유지됨을 파일만으로 확인했다. 각 명령 argv·종료 코드·출력 경로는 verification.json, stdout/stderr는 개별 로그에 있다. 합성 fixture는 새 디렉터리에만 만들고 기존 결과를 수정하지 않았다.

### 변경·보존·잔여

변경 파일은 `scripts/benchmarks/waiting-history/analyze.py`, 새 `verify_analyzer_failures.py`, 해당 README, 이 work-log 및 지정 reports/C.md다. 제품/Java 테스트/마이그레이션/다른 담당 파일은 변경하지 않았다. Python AST와 git diff --check를 통과했다.

- 분석기 전 SHA256: `9489db89ce67428bd630f86d79a518621799ee2163811d1dfcad5c21ac041867`.
- 분석기 후 SHA256: `774751d729591a339df08ba97c23c4fbae6e3924ce9eb42094b10e5f9ee3b7d7`.
- 제품·기존 테스트416파일 map SHA256: `a151765cc00ff263059e5b46087dfd90fec8dc187750e94c39a1fe3bde250352` (변경 없음).
- 고정본 `integration-inputs/20261111-C-batch`의 manifest 포함29파일(사본28개)과 기존 손실 증거89개·batch 증거206개는 모두 SHA256 일치.
- 새 증거 루트: `build/waiting-event-history/analyzer-failure-validation-1794340043296479000`; `before/`, `verification-1/`, `verification-1.log`, `preservation-and-static-checks.json`, `changed-files-sha256.json`, `tool-change.diff`를 보존한다.

기존294개 회귀·bootJar 성공과 SSE31,200행 누락0의 근거는 제품이 그대로인 `batch-validation-1794338520607415000` 결과에 연결한다. 이번에 회귀나 성능을 다시 실행했다고 주장하지 않는다. D 독점 슬롯의 실행 자원·DB·프로세스를 조회하거나 변경하지 않았다. **I 재통합과 공통 회귀는 여전히 별도**이며 기존 I1,075개 성공을 수정본의 검증으로 대신하지 않는다. 이후 도구 변경의 고정본 갱신과 통합 검증은 관리자 후속 범위다.
