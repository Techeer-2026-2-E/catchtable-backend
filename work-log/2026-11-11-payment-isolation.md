# PG 장애 격리 — 2026-11-11

## 범위와 기준

- 기준 HEAD `a44952e37993e5003c860714f126057cd311dba5`, 작업 브랜치 `feat/payment-isolation`.
- 공통 계약의 1차: 로컬 구현, 관련 회귀·장애 검증, 재현 가능한 비교 도구 준비. 커밋·push·PR·merge·배포·실제 Toss/SOLAPI·외부 문서 편집 없음.
- 현재 코드의 승인/환불/reconcile 트랜잭션 밖 호출 guard, connect/read timeout, reconcile scheduler, V33 미확정 승인 환불 outbox 및 공통 승인/환불/예약 반영 서비스를 재사용했다. notification/웨이팅 실행 코드는 변경하지 않았다.
- [Notion 기능63](https://app.notion.com/p/3d548d76fa3281c09910d755021ab870)을 읽었다. 2026-11-03의 과거 PR/완료 문구를 현재 완료 증거로 쓰지 않았다. 원문은 식별 힌트만 믿고 외부 원장으로 재확인한다는 계약이며, 현재 controller/service 및 [기존 기록](142-payment-reconcile.md)은 **Toss 서명 검증 없음**을 명시한다. 서명 검증을 새로 구현했다고 주장하지 않는다. 현재 Rest 클라이언트에는 빈 본문/해석 실패를 UNKNOWN으로 처리하는 코드가 이미 있다.

## 구현과 응답 계약

| 구분 | 동작 |
|---|---|
| 승인·환불·결제키 조회·주문 조회 | 동일 프로세스 cap 기본8. admission 대기0, 한도 초과 즉시 `503 PAYMENT_CAPACITY_EXCEEDED`, `Retry-After: 1`. 동일 주문·금액·멱등키로 재시도 |
| connect/read/전체 호출 제한 | 기존 connect5s/read30s 유지, 전체 호출 기본35s 추가. 모두 설정 가능하며 0/음수 무한 timeout을 허용하지 않음. read는 inactivity 제한, 전체 호출 제한은 응답 body 소비까지 포함한 호출자 대기 제한 |
| 전송 스레드 | PG HTTP 전용 최대8 스레드, 유한 큐8. admission permit 때문에 실행+대기 transport가 cap을 넘지 않음. DB 작업/도메인 반영은 transport 스레드에서 하지 않음 |
| 전체 제한 이후 HTTP가 계속 살아 있음 | 호출자는 UNKNOWN을 받고 반환하되 해당 permit은 실제 HTTP가 종료할 때까지 보유. timeout을 이유로 새 호출을 계속 쌓지 않음. 늦게 받은 응답으로 DB를 변경하지 않고 기존 reconcile로 복구 |
| 새 승인 한도 거절 | `ReservationPaymentService.confirm`이 승인 보호 저장 전에 admission 확보. 거절 시 새 보호·TIMEOUT·FAILED·외부 호출 없음 |
| 기존 결과 불명 승인 재시도 한도 거절 | 이전 보호와 TIMEOUT을 유지. 과거 호출이 없었다고 추정해서 보호를 해제하지 않음 |
| 환불 한도 거절 | 이미 커밋한 최초 예약 환불 의도/금액/시각/키 및 공통 `REQUESTED` 유지. PG 확정 실패인 `FAILED`나 전송 후 불명인 `UNKNOWN`으로 바꾸지 않음. 기존 reconcile이 원장 조회 후 같은 키로 복구 |
| 웹훅 | body는 현재와 같은 `data`/최상위 `orderId`, 없으면 `paymentKey` 힌트. 임의 status/금액은 적용하지 않음. 알려진 결제의 job을 DB 커밋한 뒤 200. 잘못된 JSON/빈 본문/알 수 없는 결제는 기존200. 식별 조회·job 저장 실패는5xx로 전파 |
| 사용자 sync | 소유권·역할 검사와 동기 상세 조회 유지. 실제 PG 조회 오류는 기존 현재 상태200, 새 admission 거절은503. 전송 후 UNKNOWN을 확정 실패로 바꾸지 않음 |

`PAYMENT_CAPACITY_EXCEEDED`는 payment 전용 advice에서 공통 ErrorResponse 형식으로 반환한다. ErrorCode/GlobalExceptionHandler/build.gradle은 수정하지 않았다. 예약 confirm의 기존 `15일` 재승인 제한, 공통 refund/reconcile의 동일 키·원금액·15일 확인 계약과 승인 보호 정책을 유지한다. 같은 키의 PG 호출이 재전달될 수 있으며 외부 효과의 중복 방지는 기존 PG 멱등키 계약에 의존한다.

## 영속 작업과 책임

- **V37** `payment_reconcile_job`: payment PK/FK(삭제 cascade), PENDING/PROCESSING/DONE, requested/claimed version, attempt, due, lease, last_attempt/error, created/updated. 기존 SQL은 변경하지 않았다. C의 V36은 이 트리에 없으며 통합 시 V36·V37을 함께 적용해야 한다.
- 웹훅 중복은 payment당 한 행으로 합친다. 처리 중 웹훅은 version을 올려 진행 중 작업의 성공 ack가 새 힌트를 지우지 못하게 한다. 완료된 행에 새 웹훅이 오면 다시 PENDING. 처리 중/대기 중인 행의 기존 retry due는 유지한다.
- claim은 짧은 DB 트랜잭션의 `FOR UPDATE SKIP LOCKED`와 UPDATE RETURNING. PG 호출 전에 커밋한다. lease120초. `attempt_count + claimed_version` 조건으로 이전 소비자의 ack/retry를 차단한다. 중단 시 재시작 또는 다른 인스턴스가 만료 lease를 회수한다.
- UNKNOWN/IN_PROGRESS/조회 오류/한도 초과/일시 실패는 2초부터 최대60초 backoff로 무기한 재확인한다. 임의 횟수로 영구 결제 실패를 만들지 않는다. 저장이 실패하면 lease 상태가 복구 경로다. 완료 job 행은 재사용하며 무조건 append하지 않는다.
- 기존 `PaymentReconcileScheduler`는 기존 대상(TIMEOUT, 승인 보호·격리, 진행 중 환불)을 찾아 job만 저장한다. secret-key 조건과 batch50/60초 기본값을 유지한다. 실제 worker 시도 시각 순으로 스캔해 고정 낮은 ID의 영구 불명이 새 대상을 계속 가리지 않게 했다.
- `PaymentReconcileJobWorker`는 기존 `PaymentReconcileService.reconcile`을 호출한다. 기존 `PaymentService`, `ReservationPaymentService.settle`, `PaymentRefundService`, `ReservationRefundSettlementService`가 상태·예약·환불을 반영한다. 새 결제 엔진을 만들지 않았다.
- V33 outbox는 사용자 미확정 승인 환불 **명령 인계**, V37 job은 결제 **재확인**이다. V33은 같은 요청의 공통 환불 행이 생기면 DELIVERED로 인계 완료하며, REQUESTED/UNKNOWN 최종 복구는 기존 reconcile 대상에 남는다.
- job worker/기존 reconcile scanner/V33 dispatcher만 `paymentTaskScheduler`(2 threads)를 사용한다. 공용 `taskScheduler`는 Boot builder의 기존 설정으로 명시해 기본 자동 설정이 PG scheduler 때문에 사라지지 않게 했다. 예약·웨이팅 만료와 C의 notification scheduler는 기존 공용 scheduler를 사용한다.

## 자원 경계와 남는 한계

- 동기 승인·동기 환불·사용자 sync는 요청 스레드를 사용하지만 PG 대기는 전체 제한으로 끝난다. 웹훅 요청은 외부 조회 없이 짧은 DB 작업만 한다.
- 실제 HTTP 호출은 DB connection/transaction/row lock 없이 수행한다. admission은 승인 준비의 짧은 DB 단계까지 보유하지만 DB가 PG 응답을 기다리지 않는다. 전용 worker의 claim/결과 저장도 짧은 별도 트랜잭션이다.
- Hikari/HTTP 요청 처리기/DB 서버/CPU는 앱 전체가 공유한다. cap은 프로세스당이며 복제본 전체 한도가 아니다. DB 자체 장애·잠금 지연·인증 전 요청 폭주를 별도 격리한 것은 아니다. PG scheduler 두 스레드가 모두 사용 중이면 PG 작업 사이에 지연이 생길 수 있다.
- 클라이언트 deadline/연결 종료로 원격 PG 처리가 취소된다고 단정하지 않는다. read timeout을 회피하며 무한히 body를 보내는 PG는 최대 cap만큼 transport를 계속 점유할 수 있다. 이때 추가 PG 요청은503이고 공용 API/스케줄러는 transport를 기다리지 않는다. 운영 차단/재시작 판단은 별도다.
- 서명 검증이 없는 기존 webhook 계약을 유지했으므로 알려진 식별자를 이용한 재확인 유발 가능성은 남는다. DB row dedupe와 PG cap이 영향을 제한한다. 인증 계약 변경/운영 ingress rate limit은 이번 범위가 아니다.
- 결제 최종 상태의 대시보드 직접 취소 반영, 살아 있는 선행 PG 호출과 15일 경계가 겹치는 기존 한계, 공유 DB 도입·모니터링 알림·멀티 인스턴스 실배포는 별도다. 새 retry는 기존 서비스의 정책을 변경하지 않는다.

## 검증

전용 PostgreSQL16 `ct-improve-d-20261111`, localhost55434, DB `ct_improve_d`만 사용했다. 기존 axum-postgres5375에는 접속하지 않았다. Java21.0.12.1, `--no-daemon --max-workers=2`. 테스트 키는 빈 값 또는 `local_fake_only`다.

```sh
DB_HOST=localhost DB_PORT=55434 DB_NAME=ct_improve_d \
DB_USERNAME=ct_improve_d DB_PASSWORD=ct_improve_d_local_only \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:19084 \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
./gradlew build --no-daemon --max-workers=2
```

- 관련 회귀 첫 실행255개 통과. 신규 HTTP 장애 테스트 첫11개 통과. 전체 deadline/추가 query·refund 잠금 검사 후 관련 회귀268개(신규13개 포함) 통과. 모두 실패/오류/skip0. 서로 중복된 실행이므로 합산하지 않는다.
- 신규 테스트는 네 종류 호출의 cap·503/Retry-After, 새 보호 미생성/기존 보호 유지, 독립 HTTP 원장의 승인/환불 응답 유실, PG500, duplicate job, DB 저장 실패5xx, lease119/120초·동시 소비·stale ack, worker 재구성, 처리 중 webhook version, 원래 예약 환불 의도·금액·키 복구, 15일 경계, slow-body 전체 deadline과 물리 permit 유지, 외부 HTTP 중 별도 DB 연결의 예약/결제/환불 행 NOWAIT 잠금을 검사한다.
- 실제 JVM 강제중단/재시작 기능 smoke는 별도 fake PG 파일 원장과 같은 DB에서 승인/예약 확정 및 환불 UNKNOWN 복구를 확인한다. 프로세스 smoke의 lease는 SQL로 시간을 당겼고, 시간 경계 테스트와 구분한다.
- 전체 빌드/기준본 대조 및 최종 process smoke 결과는 아래 최종 결과 절에 기록한다.

첫 process fixture 실행은 PostgreSQL GENERATED ALWAYS 명시 ID와 waiting.queue_order 필수값 누락으로 각각 실패했다. fixture를 수정했으며 제품 장애로 세지 않는다. 원본 로그를 보존했다. 작업 중 생성한 임시 폴더의 `rm -rf` 요청은 자동 승인 검토가 해당 명령 형식을 거부했다. 파일을 build 산출물 폴더로 이동해 보존했으며 삭제를 강행하지 않았다.

## 성능 비교 준비

[도구 실행법](../scripts/payment-isolation/README.md)에 정상/PG2초 지연 × 동일 기준/수정본 fixture·요청·Clock·JVM/DB/HTTP pool·워밍업·표본·반복, p50/p95/p99/오류율, PG 동시성, 요청/DB pool/lock 대기/공용 scheduler 지연 수집법을 적었다. 기준본은 자기 build 폴더의 git archive다. 성능 측정은 공통 계약의2차 순차 배정까지 **미실행**이며 개선 수치는 없다. 일반 API 부하는 매장·예약·웨이팅 **조회**이고 신규 생성 처리량은 미검증이다.

산출물 정본: 이 워크트리 `build/payment-isolation/`. JUnit 원본과 각 단계 로그, 실제 앱/PG 재시작 로그, fake PG 원장, 기준본 빌드/실패 대조를 보존한다. 현재 작업트리/브랜치/전용 DB를 후속 검증용으로 보존한다.

## 최종 결과와 인계

| 검증 | 이번 실행 결과 | 원본 |
|---|---|---|
| 관련 회귀(최종 제품 코드) | 16클래스268개, 실패/오류/skip0 | `regression-second.log`, `regression-second-xml/` |
| 전체 build | **92클래스1011개 중2개 실패**, 오류/skip0. 전체 빌드 성공으로 보고하지 않음 | `full-build.log`, `full-first-xml/` |
| 기준 a44952e의 두 실패 클래스 재현 | 2클래스36개 중 동일2개 실패, 오류/skip0 | `baseline-targeted.log`, `baseline-prepared/baseline/build/test-results/test/` |
| 실제 JVM 중단·재시작(최종 제품 코드) | 통과. 승인1회·환불1회, 원래3000원 환불/잔액7000원, PARTIAL_REFUNDED/CONFIRMED | `process-smoke-final/smoke.json`, 앱 로그·`pg-ledger.json` |
| PG 지연 중 공용 스케줄러 | 실제 hold EXPIRED와 waiting NO_SHOW 확인; 그 시점 fake PG active>0 | 위 smoke 및 로그 |
| 실제 별도 DB 연결 | PG 진행 중 reservation/payment/job NOWAIT 성공. JUnit은 confirm/query/cancel 중 reservation/payment/refund 확인 | 위 smoke + 신규13개 JUnit |
| 기준본 비교 도구 기동 | 같은 fixture의 매장/예약/웨이팅 GET 및 웹훅 각200, scheduler heartbeat 메트릭 확인 | `baseline-fixture-final/baseline-fixture.json` |
| 마이그레이션 | 기존34개 SQL 바이트 동일, V37 신규 적용 성공, ddl-auto validate 유지 | `migrations.json`, Flyway 로그 |
| 양식 | git diff --check 통과 | 최종 작업 실행 |

전체 빌드 실패는 `BusinessHourWaitingConflictTest.closingTodayWithWaitingRollsBack`와 `WaitingServiceTest.create_specialClosedDay_fails`다. 두 테스트 모두 `Expecting code to raise a throwable`이며, 같은 KST 자정 이후 환경의 **수정 전 기준본에서도 동일**했다. 테스트가 현재 시각 앞뒤의 자정 넘는 영업시간과 오늘 특별 휴무를 구성한다. 해당 정책/fixture의 원인 확정과 수정은 매장·웨이팅 담당과 관리자의 후속 범위이고 여기서는 수정하거나 skip하지 않았다.

이 작업의 1차 PG 격리 구현·관련 검증·도구 준비는 완료했다. 전체 통합 통과나 성능 개선·실제 Toss·CI·프런트 연동·공유 DB 반영·배포 완료를 뜻하지 않는다. 관리자2차 순차 부하 비교, B/C와 기존 두 실패 확인, V36/V37 통합 검증, 사용자 별도 승인 뒤 커밋/게시가 남는다. 공통 파일 변경은 `application.yml`의 Toss 설정4개와 테스트용 `application.properties`의 `payment.jobs.enabled=false` 한 줄이다. 기본 scheduler 명시 bean의 통합 충돌도 확인해야 한다.

## 2026-11-11 리뷰 후속 — 완료 승인 replay와 살아 있는 구 worker

위 1차 결과와 원본 실패 기록은 보존한다. 이번 후속은 Spec 리뷰의 D 두 항목과 Standards 리뷰의 승인·환불 문서 누락만 처리했다. A의 독점 측정 중에는 코드·테스트 작성과 읽기 분석만 했고, 관리자의 정확성 검증 슬롯 허용 메시지 `msg_6cbd104b17e3` 이후 Gradle/fake PG를 실행했다. 부하 비교는 실행하지 않았다.

### 완료 승인 replay: 제품 코드 수정

`ReservationPaymentService.confirm`의 admission이 기존 완료 응답보다 앞서 cap 포화 시 같은 승인 재요청도503이 되는 경로를 수정했다. 기존 예약→결제 잠금과 `prepare`/`finish` 검증을 재사용하여 `DONE`인 요청만 admission 없이 처리한다. 소유권·입력·금액·결제 키·예약 스냅샷·격리·승인 보호 및 예약 확정 조건을 그대로 검사한다. 단순 상태 조회만으로 응답을 만드는 경로가 아니다.

최초 검사에서 미완료면 저장 없이 빠져나와 permit을 얻은 뒤 기존 승인 준비를 수행하므로, 새 외부 승인에 대한 **보호 저장 전 admission**은 유지된다. 검사 직후 다른 요청이 승인을 커밋하는 경합도 고려하여 admission 거절 시 완료 여부를 같은 검증으로 다시 확인한다. 미완료면 원래503을 전파하고 기존 TIMEOUT 보호 네 필드를 바꾸지 않는다.

실제 HTTP fake PG와 MockMvc에서 cap1을 채운 채 완료 승인200/추가 PG0, 잘못된 키·금액409, 다른 고객404, 빈 키400, 예약 스냅샷 불일치409, 보호 누락409를 확인했다. 신규 승인503/Retry-After1/새 보호 네 필드 NULL/READY 유지, 이전 TIMEOUT 및 보호 네 필드 동일도 확인했다. 첫 검사와 admission 사이에 실제 PG 응답을 다른 스레드에서 DB 반영한 경합은 SpyBean으로 gate 진입 시점만 고정했고, 실제 gate·서비스·PG HTTP·DB를 사용해 재요청 DONE과 원장 승인1회를 검사했다.

### 살아 있는 구 worker: 검증 공백 보완, 도메인 코드 추가 수정 없음

`PaymentStaleWorkerIntegrationTest`는 localhost19084의 HTTP 서버에 DB와 별개 원장 및 멱등키별 응답을 유지한다. 원래 HTTP 호출을 latch로 살아 있게 유지하고 응답 bytes를 미리 고정한 뒤, 기존 테스트 Clock을121초 전진해120초 lease를 회수한다. 실제120초 대기나 다중 앱 배포 테스트는 아니다.

| 경합 | 검사한 결과 |
|---|---|
| 승인 효과 후 confirm 응답 지연 + 구 job의 DONE 조회 응답 지연 → 새 worker와 사용자 sync → 원래 키로3000원 부분 환불 → 구 조회/confirm 응답 해제 | job attempt2/DONE, PARTIAL_REFUNDED/CONFIRMED, 환불 COMPLETED, 승인 효과1·환불 효과1·확정 이벤트1. 늦은 DONE은 환불을 취소하거나 재확정하지 않음. 원래 confirm 호출은 이후 환불을 관찰해 기존 PAYMENT_RECONCILIATION_REQUIRED 예외로 종료 |
| 만료된 미확정 승인에 V33 환불 접수 → 첫 cancel을 PG 효과 전에 대기 + 구 job의 환불 전 DONE 응답 대기 → lease 회수 job·사용자 sync·V33 소비자 동시 진행 → 구 응답 해제 | job/outbox attempt2, DONE/DELIVERED, REFUNDED와 환불/예약 환불 요청 COMPLETED. 원래 UNCONFIRMED_REFUND 의도·10000원·키·사유·요청시각·정책 한도 보존, 승인 효과1·환불 효과1. 같은 키 HTTP cancel은 재전달되지만 효과는 중복되지 않음. 원래 예약 EXPIRED, 대체 선점 PENDING_PAYMENT, 확정 이벤트0 |

늦은 응답을 풀기 전후의 payment·payment_refund·reservation·job 전체 행을 비교했고, V33 시나리오는 outbox 전체 행·원래 환불 의도 필드·대체 예약 전체 행도 비교했다. stale ACK뿐 아니라 살아 있는 구 worker의 도메인 반영이 최신 결과를 덮어쓰지 않음을 확인했다. PG 대기 중 별도 JDBC 연결에서 reservation/payment/payment_refund/reservation_refund_request/job/outbox의 `FOR UPDATE NOWAIT`가 성공했고, `pg_stat_activity`의 idle transaction은 검사 연결1개뿐이었다.

이번 재현에서는 기존 `Payment.approve`의 같은 키 승인 no-op, 환불 누적액 감소 거절, `PaymentRefundService`의 완료 요청 재검사, reconcile의 잠금 후 진행 중 환불 재검사와 예약 settlement가 최신 상태를 보존했다. 따라서 도메인 서비스에 별도 stale-worker 엔진이나 claim 토큰 전달을 추가하지 않았다. 외부 효과1회는 fake PG가 구현한 기존 멱등키 계약 아래 검증한 결과이며 실제 Toss의 동작 증거는 아니다.

### 문서와 검증 결과

[기능10](../docs/frontend/feature-10-deposit-payment.md)과 [기능60](../docs/frontend/feature-60-refund-execution.md) 상단에 날짜를 붙여503/Retry-After1, 동일 요청·키 재시도, 신규 승인 보호 미생성과 이전 UNKNOWN 보호 유지, 환불 REQUESTED·원래 의도/금액/키 보존, 기존 sync/조회 이용을 추가했다. 과거 설명은 수정하지 않았다.

동일한 전용 PostgreSQL16/Java21 환경에서 다음을 실행했다. 공통 환경변수는 위 검증 절과 같다.

```sh
./gradlew test --tests 'com.catchtable.payment.PaymentIsolationIntegrationTest' \
  --tests 'com.catchtable.payment.PaymentStaleWorkerIntegrationTest' --no-daemon --max-workers=2
./gradlew test --tests 'com.catchtable.payment.*' \
  --tests 'com.catchtable.reservation.service.ReservationPaymentServiceTest' \
  --tests '*UnconfirmedRefund*' --no-daemon --max-workers=2
```

- 표적 최종: 2클래스18개, 실패/오류/skip0. 보호·예약 전체 행 assertion을 추가한 최종 관련 회귀: **17클래스273개, 실패/오류/skip0**. 포함 관계이므로 합산하지 않는다.
- 첫 시도는 새 테스트 lambda의 checked exception 처리2곳에서 컴파일 실패했다. 다음 시도는 만료 전 선점 해제의 정상 상태 CANCELLED를 EXPIRED로 기대한 테스트 준비 오류1건으로 실패했다. lambda 처리와 해당 선점 만료 시각만 바로잡았고 제품 결함으로 보고하지 않는다. 실패 로그/XML은 보존했다.
- 원본: `build/payment-isolation/review-followup/targeted-first.log`, `targeted-second.log` 및 `targeted-second-xml/`, `targeted-third.log` 및 `targeted-third-xml/`, 최종 `regression.log`, `regression-xml/`, `regression-summary.json`. 종료 자원은 `resources-after.json`, 최종 변경 지문은 `changed-files-sha256.json`이다. 이전 1차 폴더의 동명 지문은 덮어쓰지 않았다.
- 문서 링크와 `git diff --check` 통과. 이번 후속의 추가 제품 소스 수정은 `ReservationPaymentService`뿐이다. 테스트2개·연동 문서2개·본 작업 기록·D 보고서를 갱신했고, B/C의 Clock 테스트 파일 및 기존 마이그레이션은 수정/복사하지 않았다.
- Gradle/앱/fake PG 프로세스와18084/19084 listener 없음. 전용 DB55434는 보존했다. 성능 비교·전체 합본 회귀·V36/V37 통합·실제 Toss·CI·배포는 이번 후속에서 수행하지 않았다. 위 전체1011개 중 기존2실패와 기준본36개 중 동일2실패 기록은 그대로 남으며, B/C Clock 수정 통합 후 재검증해야 한다. 커밋·게시 없음.

## 2026-11-11 D 본 성능 비교 — 실제 실행 완료

Task `task_b629b344947e`, dispatch `ctx_f4cf9d9db4c0`. 관리자 독점 슬롯에서 작은 비교와 본 비교를 순차 실행했다. 기존 기록의 “성능 미실행”은 당시 상태이고 아래가 최신 결과다. 제품/테스트/migration 417파일은 시작 지문과 동일하다. 외부 조회·다운로드·fetch/pull·commit/push/PR/merge·배포·실제 PG 호출은 없다.

### 입력 보존과 실행

I 전달 manifest `integration-inputs/20261111-D-tools/manifest.json`과 files의3개 SHA256을 확인해 `run.py`, `fake_pg.py`, `PaymentIsolationProbe.java`만 반영했다. README는 D 환경과 지원변수/offline/smoke 관찰을 직접 보충했으며 I에만 있는 상대문서 링크는 가져오지 않았다. 기존 D 도구·build 증거·work-log·D 보고서를 `build/payment-isolation-benchmark-preservation-20261111/previous-D-tools-and-evidence.tar.gz`에 먼저 보존했다. 기존 스키마 dump `d_probe-before.sql`, 시작 지문 `before-sha256.json`, 입력 manifest 사본과 backup 지문도 같은 폴더에 있다.

| 도구 | 보완 전 SHA256 | 반영 후 SHA256 |
|---|---|---|
|`scripts/payment-isolation/run.py`|`1a844a526e04eeac73e3d3a30f3fc7712648b729ccfa97ba937b1deec9fa5a54`|`ca15bb11670addf1f13d5d923c0b1a5fd1b3b3d547d8b24e77137093664b811d`|
|`scripts/payment-isolation/fake_pg.py`|`157eaaca82313bfeeb7af4eafbe75e2d83785abb445e9b7c5ee0ef24f22e7137`|`7472ada04bc2a00acc9463f51bdf1113129b512bff1f04d8af361e2b2353bc4a`|
|`scripts/payment-isolation/README.md`|`98244a47ee6ea39c21dd1cf4a0ebe77a4c8e512321757f0cdccbf9d1b26ee0e3`|`4d949718995ba6184bc5c66f5718353074543f668a8be7d72bf3f405dcb18238`|
|`scripts/payment-isolation/java/com/catchtable/payment/PaymentIsolationProbe.java`|`caccdfec8fd6ccf73df415d9da73abafe78c927d5ebee07661adb89caf67c580`|`8d69172bce7387300346525530c1765d99e5ab0a2e31eae9a03021c9afb9888a`|

- 전용 PostgreSQL16.15 컨테이너 `ct-improve-d-20261111`,127.0.0.1:55434,DB/user `ct_improve_d`, disposable `d_probe`만 재생성했다. I DB55435·다른 트리는 수정하지 않았다.
- Java21.0.12.1, heap256–512MiB, JAVA_TOOL_OPTIONS 비움, Tomcat40/min4, Hikari10, connect500ms/read3s, 수정본 call3.5s/cap8. 빌드는 `--offline --no-daemon --max-workers=2`, probe/testClasses 컴파일만 수행했다.
- before는 기준 `a44952e37993e5003c860714f126057cd311dba5`의 로컬 git archive, after는 D 현재 소스다. A/B/C 합본 성능 비교가 아니다. 양쪽 fixture·실행 probe는 바이트 동일하며12조건의 앱 CLI 설정도 동일하다(기준본에 없는 설정은 효과 없음).
- fixture: 회원2·매장1·테이블100·선점100·결제100·웨이팅1·영업시간7. 매 JVM Clock은2030-01-20 12시KST+실제 경과시간이다. 매 반복 before-normal→before-PG2초→after-normal→after-PG2초 순서로 실행했다.
- 요청은 매장/예약/웨이팅 GET 및 웹훅 POST 각각25%다. 동일 워밍업/도착률이며 모든 조건은 새로운 fixture다. fake 요청별 smoke 관찰은 비교에서 꺼진 것을 확인했다.
- 작은 비교 `--requests 40 --warmup 4 --rate 20 --repeats 1`: 새 `pilot/`에4조건160요청, 모두200, 비어 있는 자원 시계열0, 계측 timeout0, exit0. 약67.04초(빌드/기동/종료 포함).
- 본 비교 `--requests 4000 --warmup 400 --rate 100 --repeats 3`: 새 `main/`에 **단1회**,4조건×3반복=48,000요청, API/조건/반복별1,000개, 모두200. 워밍업4,800개는 본 통계에서 제외한다. 2026-11-11 04:38:06 KST ~ 2026-11-11 04:50:03 KST, 약716.76초(빌드/기동/종료 포함), exit0. 런타임 도구/fixture 실패나 재시도·표본 삭제·조건 축소 없음.

재현 실행은 이 워크트리의 보존된 wrapper로 `python3 build/payment-isolation/performance-20261111/launch_observed.py <새폴더명> --requests 4000 --warmup 400 --rate 100 --repeats 3`이다. wrapper는 기존 compare CLI에 D 환경을 명시하고 호스트 표본만 추가한다. 작은 비교는40/4/20/1로 지정한다. 새 실행은 별도 슬롯 배정 뒤 수행하며 이 기록 때문에 반복 실행하지 않는다.

### API 지연 결과

다음은 **3회 각각의 분위수의 중앙값**이다(요청을 합친 pooled percentile이 아님). 반복별 원시 p50/p95/p99·시작 지연·오류는 `main/analysis.md`의48행 및 각 조건의 `requests.json`/`summary.json`에 모두 보존했다. 계산한 nearest-rank 분위수와 runner 요약값의 일치, index 중복/누락0을 확인했다.

| 조건 | API | before p50/p95/p99 ms | after p50/p95/p99 ms | 오류 before/after |
|---|---|---:|---:|---:|
|normal|store|1.75 / 3.75 / 5.45|2.17 / 4.44 / 5.73|0/0|
|normal|reservation|1.72 / 3.82 / 4.63|2.06 / 4.30 / 5.87|0/0|
|normal|waiting|2.09 / 4.49 / 5.79|2.57 / 5.22 / 6.93|0/0|
|normal|webhook|6.23 / 10.92 / 14.96|2.50 / 4.53 / 5.89|0/0|
|pg-delay|store|2580.71 / 3064.27 / 3134.72|2.22 / 4.98 / 6.18|0/0|
|pg-delay|reservation|2579.47 / 3064.84 / 3132.12|2.15 / 4.66 / 5.75|0/0|
|pg-delay|waiting|2578.45 / 3065.11 / 3131.73|2.63 / 5.95 / 7.22|0/0|
|pg-delay|webhook|4592.73 / 5087.91 / 5155.71|2.58 / 5.01 / 6.10|0/0|

PG2초 지연 조건에서 조회 p99는 기준본 약3.13초, 수정본5.75–7.22ms였다. 정상 조건에서는 수정본 조회 p95가 기준본보다 매장0.69ms·예약0.48ms·웨이팅0.73ms 높았다. 정상 조회까지 모두 빨라졌다고 해석하지 않는다. 웹훅 수정본 수치는 durable 접수 응답 시간이지 외부 원장 재확인 완료 시간은 아니다.

### 자원·클라이언트·계측 한계

| 반복-소스-조건 | 표본수 / 최대간격ms | Tomcat busy/current max | Hikari active/pending max | PG peak | scheduler p95/max ms | DB active/idleTX/Lock max | host load1 p50/max | host CPU sum p95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
|0-before-normal|129 / 330.77|3.00/6.00|1.00/0.00|1|94.51/144.26|3/1/0|4.80/5.19|350.80|
|0-before-pg-delay|13 / 18383.02|40.00/40.00|2.00/0.00|41|58073.10/58073.10|1/0/0|5.03/6.09|314.50|
|0-after-normal|128 / 338.50|2.00/6.00|2.00/0.00|1|0.00/0.07|2/2/0|5.50/5.70|398.70|
|0-after-pg-delay|127 / 338.81|2.00/6.00|1.00/0.00|1|0.00/1.64|2/1/0|5.18/5.63|350.90|
|1-before-normal|129 / 333.17|3.00/7.00|2.00/0.00|1|94.84/191.91|2/1/0|5.03/5.26|418.40|
|1-before-pg-delay|13 / 18333.08|40.00/40.00|1.00/0.00|41|58058.81/58058.81|1/1/0|4.60/5.23|344.10|
|1-after-normal|127 / 341.03|2.00/5.00|1.00/0.00|1|0.00/3.68|2/1/0|5.04/5.41|358.60|
|1-after-pg-delay|128 / 336.93|2.00/6.00|2.00/0.00|1|0.00/4.67|2/1/0|4.62/4.85|308.40|
|2-before-normal|129 / 333.18|2.00/6.00|1.00/0.00|1|33.79/143.66|2/2/0|4.60/5.29|321.30|
|2-before-pg-delay|13 / 18361.88|40.00/40.00|4.00/0.00|41|58171.75/58171.75|1/0/0|4.11/4.56|292.70|
|2-after-normal|128 / 340.56|2.00/6.00|1.00/0.00|1|0.00/1.53|2/1/0|4.30/4.97|312.90|
|2-after-pg-delay|127 / 336.76|2.00/6.00|1.00/0.00|1|0.00/2.86|1/1/0|4.56/4.75|399.40|

- 기준본 PG 지연의 fake PG peak는 매회41(요청 처리40+공용 scheduler 경로), 수정본은1이었다. 수정본은 한 job worker의 순차 조회와 dedupe/backoff를 사용한다. 이 부하에서 cap8 포화를 관측한 것은 아니며 승인/환불 처리량 또는 전체 시스템 처리량 개선으로 확대하지 않는다.
- 공용 scheduler 지연 max는 기준본 PG지연58,058.81–58,171.75ms, 수정본 PG지연1.64–4.67ms였다. 기준본 정상도 최대143.66–191.91ms, 수정본 정상0.07–3.68ms. 이 수치는 heartbeat 관찰이고 실제 만료 업무 검증은 I의 별도 정확성 증거다.
- Hikari active 표본 max는 기준본1–4, 수정본1–2이며 pending은 모든 표본0이었다. DB lock waiter 표본0, idle-in-transaction은0–2, active는1–3이었다(관찰 SQL 자체 포함). 순간 표본이므로 모든 lock hold 시간/DB 무대기나 PG 중 장기 transaction 부재를 이 수치만으로 증명하지 않는다. 별도 연결 NOWAIT는 기존 정확성 검증이다.
- 기준본 지연 조건의 **메트릭 조회 timeout 매회20건, 합계60건**을 그대로 보존했다. 자원 row는 매회13개, 최대 시작 간격18,333.08–18,383.02ms다. 나머지 조건은127–129개/조건, 최대 간격330.77–341.03ms, timeout0이다. 비어 있는 전체 시계열은 없지만 지연 조건의 관측은 성기고 누락 구간의 peak를 단정할 수 없다.
- metric은 같은 Tomcat의 HTTP 요청9개를 차례로 수집한 뒤250ms 기다린다. row의 `at`는 수집 시작이고 값들은 이후 순차 관찰되므로 동일 순간 snapshot이 아니다. 서버 포화 시 수집이 늦거나 timeout되며 마지막 in-flight 수집이 요청 완료 뒤 끝날 수 있다. 계측 요청 자체도 부하이고 관측 빈도가 조건마다 달라지는 한계가 있다.
- 기준본에 없는 PG active/admitted/rejected 메트릭은 null이다. 수정본 rejected는 거절이 발생해야 등록되는 counter여서 전부 null이고0 측정값으로 바꾸지 않았다. 본 업무 요청은 모두200이며 이 부하에서503 동작을 추가 검증했다고 주장하지 않는다.
- 클라이언트는 최대256 threads다. 기준본 PG지연의 API별 시작지연 p99 최대는7,634.54/7,667.43/7,663.04ms, 전체 max7,801.09ms다. 목표100RPS를 실제 시작 시점에 유지하지 못했으므로 외부 도착률100RPS에서의 무제한 안정 처리라고 해석하지 않는다. 예정 도착→완료 p99의 API별 최대는12,224.18/12,270.50/12,244.71ms였다. 나머지 조건의 시작지연 max는12.59ms 이하이며 원시 값과 합산 지연을 `analysis.json`에 별도 보존했다.
- 호스트는 논리 CPU18개다. 본 실행 전체675표본의 load1은3.77–6.09, 측정 구간의 host CPU합계 p95는292.7–418.4%(코어별100% 합산)였다. 이는 ps의 host/process snapshot이며 앱의 독점 CPU 사용률이 아니다. 표본 수집 자체는최대114.47ms. vm_stat 기준 실행 전체 Pageouts +69, Swapins/Swapouts +0/+0을 기록했고 다른 호스트 작업 영향/순서효과를 배제하지 않는다.
- PG calls 마지막 표본(워밍업 및 background 포함)은 기준 정상2893–2897/지연1129, 수정 정상495/지연22다. 같은 webhook 입력이 같은 수의 PG 조회를 완료했다는 뜻이 아니다. fake 원장은 IN_PROGRESS로 유지되어 재확인 완료/정상 결제 효과를 측정하지 않으며, 종료 시 durable job99개 중1개 PROCESSING이 남은 dump도 보존했다. 재확인 처리 지연/queue drain 완료를 이 비교로 주장하지 않는다.

### 증거·정확성 연결·종료

- 산출물 정본: `build/payment-isolation/performance-20261111/`. `pilot/`, `main/` 아래 조건별 요청/자원/요약/앱명령/로그, 상위 invocation/exit/host JSONL, 분석·렌더링·실행 wrapper, `tool-changes.json`, `condition-invariants.json`, `resources-after.json`, 최종 `d_probe-after.sql`을 보존했다. 입력 전 보존 폴더와 원래273/1011/36개 증거는 덮어쓰지 않았다.
- D 새 상태 단언2개의 기존 “실행 대기”는 I 합본에서 해소됐다. I의 `05-full-test-config/xml/TEST-com.catchtable.payment.PaymentStaleWorkerIntegrationTest.xml`을 읽어2개/실패0/오류0/skip0과 현재 테스트 SHA256 `6a6cf6c9291bad0c8c1824398a09f7ccc2124c63efb45f0d99d6c1e0b45fcdb1`을 대조했다. I 전체1075 통과 및 I-SPEC-1 실제restart/scheduler는 I 보고서/증거를 참조하며 이번 D 재실행이라고 쓰지 않는다. D의 과거1011 중2실패와 baseline36 중동일2실패도 과거 원본으로 보존한다.
- 종료 시 앱18084/fake19084 listener0, Gradle/앱/fake 프로세스0, 확인 psql 외 DB연결0. 자기DB55434와 최종 d_probe 자료는 보존했다. dump에서100선점/100결제/100테이블/1매장/1웨이팅도 재확인했다.
- 이번 제품 변경은 없으므로 새 제품 공통회귀 필요도 없다. Python 구문·문서 상대링크·git diff --check 통과. 로컬 조회부하 격리 결과이며 생성 처리량·실제 Toss/SOLAPI·CI·공유DB·다중앱·배포는 미검증이다. 사용자 정책 의사결정 감사와 별도 승인 후 커밋/게시가 남는다.

### 본 비교 반복별 API 전체 집계


지연/시작 지연은 ms, nearest-rank이며 각 행을 독립 보존한다.

| 반복-소스-조건 | API | n | p50 | p95 | p99 | 오류 | 시작지연 p99 / max |
|---|---|---:|---:|---:|---:|---:|---:|
|0-before-normal|store|1000|1.73|3.54|4.92|0|5.02 / 5.66|
|0-before-normal|reservation|1000|1.69|3.60|4.56|0|5.01 / 5.29|
|0-before-normal|waiting|1000|2.05|4.30|5.79|0|4.97 / 7.00|
|0-before-normal|webhook|1000|6.08|10.86|13.46|0|5.03 / 6.29|
|0-before-pg-delay|store|1000|2594.23|3059.47|3136.50|0|7634.54 / 7782.08|
|0-before-pg-delay|reservation|1000|2592.54|3060.65|3136.26|0|7630.07 / 7773.14|
|0-before-pg-delay|waiting|1000|2591.12|3065.11|3136.78|0|7622.57 / 7763.75|
|0-before-pg-delay|webhook|1000|4603.62|5087.91|5155.71|0|7612.99 / 7755.21|
|0-after-normal|store|1000|2.17|4.73|6.06|0|5.01 / 6.02|
|0-after-normal|reservation|1000|2.06|4.68|5.87|0|5.03 / 12.59|
|0-after-normal|waiting|1000|2.57|5.63|6.93|0|5.03 / 6.78|
|0-after-normal|webhook|1000|2.54|4.86|5.93|0|5.02 / 7.97|
|0-after-pg-delay|store|1000|2.41|5.20|6.18|0|5.02 / 7.50|
|0-after-pg-delay|reservation|1000|2.31|5.17|6.64|0|5.05 / 7.08|
|0-after-pg-delay|waiting|1000|2.89|6.47|7.91|0|5.02 / 6.87|
|0-after-pg-delay|webhook|1000|2.80|5.38|7.17|0|5.05 / 8.91|
|1-before-normal|store|1000|1.91|4.06|5.45|0|5.02 / 7.62|
|1-before-normal|reservation|1000|1.92|4.22|5.29|0|5.05 / 6.53|
|1-before-normal|waiting|1000|2.32|4.82|6.94|0|5.02 / 6.07|
|1-before-normal|webhook|1000|6.71|12.00|15.16|0|5.05 / 8.08|
|1-before-pg-delay|store|1000|2580.71|3064.27|3131.46|0|7667.43 / 7790.95|
|1-before-pg-delay|reservation|1000|2579.47|3064.84|3131.37|0|7658.38 / 7785.38|
|1-before-pg-delay|waiting|1000|2578.45|3064.93|3130.76|0|7650.62 / 7777.97|
|1-before-pg-delay|webhook|1000|4592.73|5082.05|5159.90|0|7640.84 / 7768.35|
|1-after-normal|store|1000|2.17|4.44|5.73|0|5.06 / 6.85|
|1-after-normal|reservation|1000|2.07|4.30|5.81|0|5.04 / 6.63|
|1-after-normal|waiting|1000|2.58|5.08|7.06|0|5.03 / 5.14|
|1-after-normal|webhook|1000|2.50|4.53|5.89|0|5.04 / 9.69|
|1-after-pg-delay|store|1000|2.22|4.98|6.49|0|5.08 / 9.37|
|1-after-pg-delay|reservation|1000|2.15|4.64|5.75|0|5.04 / 7.78|
|1-after-pg-delay|waiting|1000|2.63|5.95|7.22|0|5.02 / 6.57|
|1-after-pg-delay|webhook|1000|2.58|5.01|6.10|0|5.04 / 10.45|
|2-before-normal|store|1000|1.75|3.75|5.46|0|5.03 / 5.37|
|2-before-normal|reservation|1000|1.72|3.82|4.63|0|5.03 / 7.70|
|2-before-normal|waiting|1000|2.09|4.49|5.69|0|5.01 / 6.89|
|2-before-normal|webhook|1000|6.23|10.92|14.96|0|5.04 / 9.86|
|2-before-pg-delay|store|1000|2565.70|3087.88|3134.72|0|7663.04 / 7801.09|
|2-before-pg-delay|reservation|1000|2564.93|3087.69|3132.12|0|7653.85 / 7792.27|
|2-before-pg-delay|waiting|1000|2565.49|3087.28|3131.73|0|7644.07 / 7783.76|
|2-before-pg-delay|webhook|1000|4579.13|5103.05|5151.66|0|7636.28 / 7776.80|
|2-after-normal|store|1000|2.08|4.44|5.60|0|5.03 / 6.23|
|2-after-normal|reservation|1000|1.95|4.28|6.35|0|5.04 / 7.87|
|2-after-normal|waiting|1000|2.42|5.22|6.86|0|5.03 / 8.33|
|2-after-normal|webhook|1000|2.41|4.50|5.73|0|5.01 / 9.11|
|2-after-pg-delay|store|1000|2.17|4.96|6.02|0|5.04 / 6.47|
|2-after-pg-delay|reservation|1000|2.06|4.66|5.75|0|5.03 / 5.47|
|2-after-pg-delay|waiting|1000|2.57|5.78|7.01|0|5.02 / 6.85|
|2-after-pg-delay|webhook|1000|2.52|4.91|6.05|0|5.05 / 8.25|
