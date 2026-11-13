# PG 호출 격리 PR 분리 검증

- 작업일: 2026-11-13 KST
- 기준: 웨이팅 업무·알림톡 이력 PR의 끝 커밋 위, 브랜치 `feat/pg-call-isolation`. 그 PR의 기준과 검증은 [분리 검증 기록](2026-11-13-waiting-event-log-pr-split.md)에 있다.
- 관련 계약: [기능 10](../docs/frontend/feature-10-deposit-payment.md), [기능 58](../docs/frontend/feature-58-payment-status.md), [기능 60](../docs/frontend/feature-60-refund-execution.md)
- 이전 구현·측정: [2026-11-11 기록](2026-11-11-payment-isolation.md)

## 배경과 범위

백엔드 개선 합본을 다섯 PR로 나눈 것 중 네 번째다. PG(Toss) 호출이 느려질 때 요청과 공용 스케줄러가 함께 묶이지 않도록 하는 부분과 영속 재확인 작업(V37)만 담았다. 뒤따르는 웨이팅 SSE 영속화(V38·V39)는 들어 있지 않다.

| 구분 | 내용 |
| --- | --- |
| 제품 코드 15파일 | 새 `BoundedTossPaymentsClient`, `PaymentCallGate`, `PaymentCapacityExceededException`, `PaymentSchedulingConfig`, `PaymentCapacityExceptionHandler`, `PaymentReconcileJobRepository`, `PaymentReconcileJobWorker`. 수정 `TossPaymentsClient`, `TossPaymentsConfig`, `PaymentWebhookController`, `PaymentRepository`, `PaymentReconcileScheduler`, `PaymentReconcileService`, `ReservationPaymentService`, `ReservationRefundOutboxScheduler` |
| 설정 | `application.yml`의 Toss 4항목(연결·읽기 제한의 환경변수 지정, 전체 호출 제한, 동시 호출 한도). 테스트 설정에 `payment.jobs.enabled=false` |
| 마이그레이션 | V37 추가. 기존 V1~V36 파일은 바꾸지 않았다 |
| 테스트 4파일 | 새 `PaymentIsolationIntegrationTest`, `PaymentStaleWorkerIntegrationTest`. 수정 `PaymentReconcileControllerTest`, `PaymentReconcileServiceTest` |
| 도구·문서·기록 | [PG 격리 검증 도구](../scripts/payment-isolation/README.md) 6파일, [2026-11-11 통합 검증 도구](../scripts/backend-improvement-integration/README.md) 2파일, 위 기능 문서 3개, 2026-11-11 기록 2개, 이 기록 |

동시 PG 호출은 프로세스당 한도(기본 8)를 두고, 넘으면 기다리지 않고 `503 PAYMENT_CAPACITY_EXCEEDED`와 `Retry-After: 1`로 거절한다. 전체 호출 제한(기본 35초)을 넘기면 호출자는 결과 불명으로 돌아오고, 살아 있는 HTTP가 끝날 때까지 한도 자리를 잡아 둔다. 결제 승인은 동기 응답 그대로다. 새 승인은 승인 보호를 저장하기 전에 자리를 확보하고, 이미 끝난 승인의 재요청은 PG 호출 없이 기존 검증대로 응답한다. 환불이 한도로 거절돼도 저장해 둔 환불 의도·금액·키와 `REQUESTED`·`UNKNOWN` 상태는 유지한다.

웹훅은 식별 힌트만 읽어 재확인 작업을 DB에 커밋한 뒤 200으로 응답한다. 식별 조회나 저장이 실패하면 5xx다. 전용 워커가 기존 재확인 서비스를 실행하고, 작업의 version·attempt·lease로 중단된 워커의 늦은 결과를 막는다. PG 작업은 전용 스케줄러(2 스레드)를 쓰고 공용 스케줄러 설정은 그대로 둔다. 결과 불명을 확정 실패로 바꾸지 않으며 웹훅 서명 검증은 이번에도 없다.

## 합본과 다른 중간 형태

이 PR만으로 빌드와 테스트가 되도록, 합본에서 SSE 영속화에 속한 부분을 뺀 형태로 넣은 파일이 7개 있다. 나머지 28개는 합본과 내용이 같다. 다만 2026-11-11 기록의 `JAVA_HOME` 절대 경로 1줄을 `java_home` 조회로 바꾸고 끝의 빈 줄을 지웠다.

| 파일 | 이 PR의 형태 |
| --- | --- |
| `application.yml` | Toss 4항목만. `waiting.sse` 설정은 없다 |
| 테스트 `application.properties` | `payment.jobs.enabled=false` 한 줄만 추가 |
| `PaymentIsolationProbe`, `scripts/payment-isolation/run.py` | SSE 계측 클래스 등록과 그 callback 인자가 없다. PG smoke·비교 코드는 `JAVA_HOME` 기본값의 절대 경로를 `java_home` 조회로 바꾼 1줄 말고는 합본과 같다 |
| 도구 README 2개, 2026-11-11 통합 기록 | 아직 저장소에 없는 SSE 영속화 도구·문서를 설명하던 부분을 빼고 2026-11-13 안내를 넣었다. 나머지 원문은 그대로다 |

## 검증

Java Temurin 21.0.12.1, 기존 Gradle 9.7.1 캐시, 캐시된 PostgreSQL 16.15 이미지를 썼다. 이 작업 전용 컨테이너 `ct-pr-cde-20261113`(`127.0.0.1:55443`)의 빈 데이터베이스 `ct_pr_cde_d_1`에서 Flyway가 처음부터 실행됐다. 합성 로컬 계정만 넣었고 실제 `.env`·Toss·SOLAPI·기존 DB는 쓰지 않았다. `PATH`·`HOME` 같은 기본 변수 말고는 호출 환경의 변수를 Gradle에 넘기지 않았다. 실행 명령과 나머지 환경변수는 [앞 PR의 기록](2026-11-13-waiting-event-log-pr-split.md#검증)과 같고 `DB_NAME`만 다르다.

```bash
./gradlew build --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks \
  -Dorg.gradle.java.installations.auto-download=false --console=plain
```

| 검사 | 결과 |
| --- | --- |
| 전체 build 1회 | exit 0, Gradle 1분 2초, 8개 task 모두 실행. 재시도와 수정은 없었다 |
| JUnit XML 직접 집계 | 103클래스 1,156건, 실패 0·오류 0·건너뜀 0. 테스트 annotation이 있는 소스 103개와 XML 클래스가 일치하고 로그의 PASSED 1,156줄과 일치 |
| 이번에 추가·수정한 테스트 | `PaymentIsolationIntegrationTest` 16, `PaymentStaleWorkerIntegrationTest` 2, `PaymentReconcileControllerTest` 8, `PaymentReconcileServiceTest` 15 |
| 기존 결제·환불 회귀 | `ReservationPaymentServiceTest` 46, `PaymentReconcileConcurrencyTest` 3, `PaymentIdempotencyConcurrencyTest` 4, `ReservationRefundExecutionTest` 42, `UnconfirmedRefundOutboxTest` 20, `UnconfirmedRefundScheduledTest` 1 포함 |
| 빈 DB의 Flyway | 36행(V1~V37, V23은 원래 없음) 모두 success. 각 checksum이 파일의 줄 단위 CRC32와 일치 |
| bootJar | main 클래스 305개가 컴파일 결과와 같고 마이그레이션 36개 포함. probe·테스트 전용 클래스와 테스트용 `spring.properties`는 없다 |
| probe 컴파일 | `./gradlew -I scripts/payment-isolation/probe.gradle probeClasspath`가 SSE 계측 클래스 없이 성공. 컴파일만 했고 probe를 실행하지는 않았다 |
| 범위 | SSE 영속화의 클래스·테이블·설정을 쓰는 코드가 없음을 소스 검색으로 확인 |
| 문서 | 바뀐 Markdown의 상대 링크가 이 커밋의 트리에서 모두 열림. `git diff --check` 통과 |

표의 클래스별 건수는 전체 1,156건에 포함된 수이며 따로 더하지 않는다. 새 통합 테스트 2개는 `127.0.0.1:19084`에 테스트용 HTTP 서버를 띄운다. 그 포트를 다른 프로세스가 쓰고 있으면 실패한다.

실행하지 않은 것: PG 격리 검증 도구(`run.py`의 smoke·비교)와 `verify.py`, 실제 프로세스 kill·재시작, 새 성능 측정, 기존 자료가 있는 V36 DB에서 V37로 올리는 별도 실행. CI·프런트 연동·실제 Toss·배포도 이 로컬 검증에 없다.

## 남은 점

- 2026-11-11 기록의 48,000요청 비교와 1,083건은 당시 고정본의 결과다. 이 PR의 측정값이 아니다.
- `verify.py`는 당시 고정본의 소스 지문만 받는다. 지금 소스에서는 실행 전에 거절된다. 이 PR을 검증하는 도구가 아니다.
- 2026-11-11 기록과 도구 README의 `build/…` 경로와 내부 보고서 이름은 당시 로컬 증거 위치다. 이 저장소에는 없다.
- 동시 호출 한도는 프로세스 단위다. 여러 인스턴스 합산 한도나 DB 연결 풀·CPU 분리는 아니다.
- V37 적용 뒤 코드를 되돌릴 때는 진행 중인 PG 호출과 남은 재확인 작업을 먼저 확인해야 한다.
