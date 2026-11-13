# 웨이팅 SSE 영속화 PR 분리 검증

- 작업일: 2026-11-13 KST
- 기준: PG 호출 격리 PR의 끝 커밋 위, 브랜치 `feat/waiting-sse-journal`. 앞 두 PR의 기준과 검증은 [웨이팅 이력 분리 기록](2026-11-13-waiting-event-log-pr-split.md)과 [PG 호출 격리 분리 기록](2026-11-13-pg-call-isolation-pr-split.md)에 있다.
- 계약: [웨이팅 SSE 영속 전달 원장](../docs/waiting-sse-durability.md)
- 이전 구현·측정: [영속 SSE 기록](2026-11-11-waiting-sse-durability.md), [poll 처리량 기록](2026-11-11-waiting-sse-throughput.md), [합본 검증 기록](2026-11-12-backend-pr-integration.md), [문서·도구 보완 기록](2026-11-12-backend-review-docs-tools.md)

## 배경과 범위

백엔드 개선 합본을 다섯 PR로 나눈 것 중 마지막이다. 웨이팅 SSE를 보내야 할 작업과 전송 시도, 관찰 결과를 DB(V38·V39)에 남기고, 전송 경로를 전용 worker로 바꾼다. 이 PR까지 합치면 저장소의 제품 코드와 테스트가 합본과 같아진다.

| 구분 | 내용 |
| --- | --- |
| 제품 코드 13파일 | 새 `notification/durability`의 6파일과 `WaitingSseJournalRepository`. 수정 `WaitingQueueNotifier`, `NotificationService`, `NotificationEventListener`, `SseEmitterRepository`, 그리고 두 이벤트 DTO의 내부 전달 키(`deliveryKey`) |
| 설정 | `application.yml`의 `waiting.sse` 9항목. 테스트 설정에 `waiting.sse.scheduling-enabled=false` |
| 마이그레이션 | V38(작업·대상·시도·관찰·용량 테이블), V39(운영 조회용 view). 기존 V1~V37 파일은 바꾸지 않았다 |
| 테스트 17파일 | 새 `WaitingSseDurabilityTest`, `WaitingSseWorkerBurstTest`, `WaitingSseMigrationTest`, `WaitingSseOperatorPrivilegeTest`, 테스트용 `WaitingSseTestDrain`. 기존 테스트 12개를 worker 방식과 내부 전달 키에 맞췄다 |
| 도구 | `verify_sse.py`, `IntegrationSseProbe`, [처리량 측정 도구](../scripts/waiting-sse-throughput/README.md) 4파일, 운영 조회 SQL 2개, 앞 PR에서 뺐던 probe 등록과 runner callback |
| 문서·기록 | 계약 문서 1개, `docs/PROJECT.md`, 기능 문서 7개(17·18·25·44·47·49·50), 기록 4개, 앞 PR에서 안내로 바꿔 둔 README 3개와 기록 2개의 원문, 이 기록 |

웨이팅 상태 변경과 V36 이력, SSE 작업(root)을 같은 트랜잭션에 저장한다. 작업 저장이 실패하거나 보관 한도를 넘으면 상태 변경도 롤백되고 `503`과 `Retry-After: 30`으로 응답한다. (2026-11-13 정정: 503은 보관 한도·메시지 크기 초과에만 해당한다. [덧붙임](#자동-리뷰-지적-반영-2026-11-13-덧붙임) 참고.) worker는 시도를 먼저 커밋한 뒤 트랜잭션과 DB 연결 밖에서 전송하고 결과를 기록한다. 이미 커밋된 업무는 전송 실패로 되돌리지 않는다. 전송 중 중단돼 결과를 알 수 없는 시도는 `UNKNOWN`으로 남기고 다시 보내지 않는다. 연결이 없던 수신자에게 재연결 뒤 과거 알림을 다시 보내지도 않는다. 클라이언트가 수신·재연결 뒤 현재 상태를 다시 조회하는 기존 계약은 그대로다.

SSE event 이름과 payload는 바꾸지 않았다. 서버의 `send` 반환은 고객 수신 확인이 아니다. 여러 서버 사이의 전달 보장은 없다. 예약 SSE와 알림톡은 기존 경로를 쓴다. 한 번의 poll이 처리하는 최대 64건·250ms는 작업 사이에 확인하는 예산이며, 이미 막힌 전송을 끊는 제한 시간이 아니다.

## 합본과의 대조

이 PR의 끝 트리를 합본 작업본(다섯 PR로 나누기 전의 통합 작업 트리, 이 저장소에는 없다)과 파일 단위로 비교했다.

| 항목 | 결과 |
| --- | --- |
| 합본의 파일 589개 | 모두 이 트리에 있다 |
| 합본이 기준 `dev`에서 바꾼 134개 | 122개가 바이트 단위로 같다 |
| 다른 6개 | 예약 가능 시간 PR과 영업시간 검증 PR에서 리뷰 뒤 고친 도구 2개씩과 2026-11-11 기록 1개씩. 그 PR의 내용을 유지했다 |
| 그 밖에 다른 6개 | 사용자 폴더 절대 경로를 상대 경로나 `java_home` 조회로 바꾼 5개(2026-11-11 기록 2개, 도구 README 1개, 도구 스크립트 2개)와 끝의 빈 줄만 지운 README 1개. 그 밖의 내용은 같다 |
| 이 트리에만 있는 파일 | 다섯 PR의 분리 검증 기록 5개 |
| `src/main` | 347개. 합본과 전부 같다 |

앞의 두 PR에서 중간 형태로 넣었던 14개 파일은 이 PR에서 합본과 같은 내용이 됐다. 위 표의 경로·빈 줄 정리만 다르다.

## 검증

Java Temurin 21.0.12.1, 기존 Gradle 9.7.1 캐시, 캐시된 PostgreSQL 16.15 이미지를 썼다. 이 작업 전용 컨테이너 `ct-pr-cde-20261113`(`127.0.0.1:55443`)의 빈 데이터베이스에서 Flyway가 처음부터 실행됐다. 합성 로컬 계정만 넣었고 실제 `.env`·Toss·SOLAPI·기존 DB는 쓰지 않았다. `PATH`·`HOME` 같은 기본 변수 말고는 호출 환경의 변수를 Gradle에 넘기지 않았다. 실행 명령과 나머지 환경변수는 [웨이팅 이력 분리 기록](2026-11-13-waiting-event-log-pr-split.md#검증)과 같고 `DB_NAME`, `DB_USERNAME`만 다르다.

```bash
./gradlew build --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks \
  -Dorg.gradle.java.installations.auto-download=false --console=plain
```

전체 build는 두 번 실행했다.

| 실행 | DB와 계정 | 결과 |
| --- | --- | --- |
| 1회 | `ct_pr_cde_e_1`, 계정 `ct_pr_cde` | exit 1. 107클래스 1,204건 중 실패 1, 오류 0, 건너뜀 0 |
| 2회 | `ct_pr_cde_e_2`, 계정 `catchtable` | exit 0. 107클래스 1,204건, 실패 0·오류 0·건너뜀 0. Gradle 1분 11초, 8개 task 모두 실행 |

1회의 실패는 `WaitingSseOperatorPrivilegeTest`다. 이 테스트는 조회 전용 계정이 DB 소유 역할 `catchtable`의 구성원이 아님을 확인하는데, 역할 이름이 코드에 고정돼 있다. 1회에 쓴 DB에는 `catchtable` 역할이 없어서 `role "catchtable" does not exist`로 실패했다. 2회는 Compose·CI 기본값과 같은 이름의 계정 `catchtable`을 전용 컨테이너에 만들어 그 계정으로 실행했다. 코드와 테스트는 바꾸지 않았다. 두 실행에서 다른 것은 새 빈 DB와 DB 계정 이름뿐이다.

| 검사 | 결과 (2회 실행 기준) |
| --- | --- |
| JUnit XML 직접 집계 | 107클래스 1,204건, 실패 0·오류 0·건너뜀 0. 테스트 annotation이 있는 소스 107개와 XML 클래스가 일치하고 로그의 PASSED 1,204줄과 일치 |
| 새 테스트 | `WaitingSseDurabilityTest` 28, `WaitingSseWorkerBurstTest` 12, `WaitingSseMigrationTest` 2, `WaitingSseOperatorPrivilegeTest` 1 |
| 맞춰 바꾼 기존 테스트 | `WaitingEventHistoryTest` 20(앞 PR 15), `WaitingQueueNotifierTest` 7, `OwnerWaitingServiceTest` 51, `OwnerWaitingControllerTest` 19, `WaitingExpirySchedulerTest` 3, `WaitingDeferServiceTest` 9, `NotificationServiceTest` 6, `NotificationRequiredFirstTest` 8, `NotificationSettingServiceTest` 8, `NotificationControllerTest` 6, `SseEmitterRepositoryTest` 6, `OwnerReservationFeatureServiceTest` 4 |
| 빈 DB의 Flyway | 38행(V1~V39, V23은 원래 없음) 모두 success. 각 checksum이 파일의 줄 단위 CRC32와 일치. 1회 실행의 DB도 같다 |
| V37 → V38 → V39 | `WaitingSseMigrationTest` 2건이 자료를 넣은 V37 스키마에 V38을, 자료를 넣은 V38 스키마에 V39를 적용해 기존 자료와 Flyway checksum이 그대로임을 확인 |
| 운영 조회 권한 | `WaitingSseOperatorPrivilegeTest`가 별도 LOGIN 계정으로 V39 view 조회는 되고 원문 message 조회와 쓰기 8가지는 거부됨을 확인 |
| bootJar | main 클래스 323개가 컴파일 결과와 같고 마이그레이션 38개 포함. probe·테스트 전용 클래스와 테스트용 `spring.properties`는 없다 |
| probe 컴파일 | `scripts/payment-isolation/probe.gradle`의 `probeClasspath`와 `scripts/waiting-sse-throughput/probe.init.gradle`의 `waitingSseThroughputClasses`가 성공. 컴파일만 했고 probe를 실행하지는 않았다 |
| 문서 | 바뀐 Markdown의 상대 링크가 이 커밋의 트리에서 모두 열림. `git diff --check` 통과 |

표의 클래스별 건수는 전체 1,204건에 포함된 수이며 따로 더하지 않는다.

실행하지 않은 것: `verify_sse.py`(다른 고정 환경 전용이다), 처리량 측정 도구, 실제 프로세스 kill·재시작, 새 성능 측정, 테스트 밖에서의 별도 upgrade 실행. CI·프런트 연동·실제 고객 수신·배포도 이 로컬 검증에 없다.

## 남은 점

- 2026-11-11·2026-11-12 기록의 1,126건·1,204건과 처리량 수치는 당시 합본의 결과다. 이 기록의 1,204건은 2026-11-13에 이 트리로 새로 실행한 것이고, 성능은 새로 측정하지 않았다.
- `WaitingSseOperatorPrivilegeTest`는 DB 소유 역할 이름이 `catchtable`이어야 통과한다. Compose와 CI 기본값에서는 맞지만, 다른 계정 이름의 테스트 DB에서는 실패한다. (2026-11-13: 해소했다. [덧붙임](#자동-리뷰-지적-반영-2026-11-13-덧붙임) 참고.)
- 과거 기록과 도구 README의 `build/…` 경로와 내부 보고서 이름은 당시 로컬 증거 위치다. 이 저장소에는 없다.
- 완료된 작업도 보관 한도(기본 100,000건)에 들어가며 자동으로 지워지지 않는다. 운영 용량과 보존 기간은 정해지지 않았다.
- 기존 전송 방식과 새 worker를 함께 켜는 전환 경로는 없다. 되돌릴 때는 전송 중지, 진행 중 시도, 남은 작업을 먼저 확인해야 한다.

## 자동 리뷰 지적 반영 (2026-11-13 덧붙임)

세 PR에 달린 자동 리뷰 지적 16건(같은 내용 2쌍 포함)을 코드로 확인해 이 브랜치 끝에 반영했다. 제품 코드(`src/main`)와 마이그레이션은 바꾸지 않았다.

### 정정: 503이 나가는 조건

위 "배경과 범위"의 "작업 저장이 실패하거나 보관 한도를 넘으면 … `503`과 `Retry-After: 30`으로 응답한다"는 부정확했다. `503 WAITING_SSE_CAPACITY_EXCEEDED`는 보관 한도와 메시지 크기를 넘을 때만 나간다. [계약 문서](../docs/waiting-sse-durability.md)는 이 두 경우만, 기능 문서 7개는 한도 초과만 503으로 약속하며 코드와 일치한다. 그 밖의 작업 저장 실패는 상태 변경이 함께 롤백되고 공통 오류 응답이 나간다.

임시 테스트(커밋하지 않음)로 `waiting_sse_root` INSERT 실패와 `ACCEPTED` 관찰 INSERT 실패를 각각 일으켜 `POST /api/owner/waitings/{id}/call`을 호출했다. 두 경우 모두 `500 INTERNAL_SERVER_ERROR`였고 `Retry-After`는 없었다. 웨이팅 상태·호출 차수·`CALLED` 이력·root·보관 수는 그대로였다. 저장 실패도 503으로 바꿀지는 응답 계약을 새로 정하는 일이라 이번에 정하지 않았다. 계약 문서에는 503의 범위를 한 문장 보탰다.

### 바꾼 것

| 대상 | 문제 | 변경 | 확인 |
| --- | --- | --- | --- |
| `WaitingSseOperatorPrivilegeTest` | 구성원 여부를 볼 역할 이름이 `catchtable`로 고정돼 있었다 | fixture view의 실제 소유 역할을 읽어 바인딩 매개변수로 넘긴다 | 계정 `ct_pr_cde`와 `catchtable`의 DB에서 각각 통과했고 바인딩된 역할은 각 계정 이름이었다. 조회 계정에 실제 소유 역할의 membership을 임시로 주면 새 단언은 실패하고 옛 고정 이름 단언은 통과했다 |
| `PaymentIsolationIntegrationTest`의 지연 테스트 2개 | 잠금·한도 자리 관찰이 read timeout 300ms보다 길어지면 자리가 먼저 풀려 거절 단언이 실패할 수 있었다 | 승인된 PG 호출을 gate 안에 붙잡아 두는 차단 장치를 두고 `finally`에서 푼다. timeout 설정과 단언은 그대로다 | 관찰 구간에 600ms 지연을 임시로 넣으면 수정 전에는 거절 단언이 실패하고 수정 후에는 통과했다. 그 시점에 대기 중인 호출은 끝나지 않았고 결제는 아직 `READY`였다 |
| `scripts/payment-isolation/run.py` | 메트릭 확인이 `sampling_error`도 통과로 보았다. 표본 수집 스레드가 멈춰도 정상 요약으로 끝났다 | 측정값 목록일 때만 통과한다. 수집이 멈추면 결과 파일을 남기고 실행을 실패로 끝낸다 | 가짜 HTTP·SQL로 `metric()`과 `measure()`를 수정 전후 직접 실행해 비교했다. 정상 경로의 결과는 같았다. 도구 자체는 실행하지 않았다 |
| 문서 | 기능49의 재조회 대상이 불명확했다. 단일 인스턴스 조건의 한계와 과거 비교 도구의 제약이 드러나지 않았다 | [기능49](../docs/frontend/feature-49-waiting-no-show.md), [계약 문서](../docs/waiting-sse-durability.md), [과거 비교 도구 README](../scripts/benchmarks/waiting-history/README.md)를 보완했다 | 컨트롤러·서비스·도구 코드와 대조했다 |

위 "검증"의 1회 실패(`role "catchtable" does not exist`)는 다시 재현하지 못했다. 그 뒤 같은 컨테이너에 `catchtable` 역할이 만들어져서, 수정 전 테스트도 계정 `ct_pr_cde`의 DB에서 통과한다. 다만 그때는 실제 소유 역할이 아닌 역할을 검사한다.

### 바꾸지 않은 것

- 여러 인스턴스에서 연결이 없는 인스턴스가 작업을 가져갈 수 있다는 지적: 소유 인스턴스 기록이나 공유 전달은 설계 변경이라 이번 범위가 아니다. 단일 활성 인스턴스 조건과 어길 때의 결과를 계약 문서에 적었다.
- `scripts/benchmarks/waiting-history/analyze.py` 3건(`-O` 실행, 반복 번호 중복, 출력 경로가 입력 폴더 안인 경우): 이 파일은 [당시 기록](2026-11-11-waiting-event-history.md)에 적힌 SHA256과 같은 상태다. 바꾸면 보존한 검산 증거와의 대응이 끊기고, 다시 검산할 고정 입력은 이 저장소에 없다.
- `verify_sse.py`의 재연결 스트림 관찰, `waiting-history/run.py`의 실행 폴더 추가 검사: 전용 고정 환경 없이는 수정 뒤 동작을 확인할 수 없다. 뒤의 것은 운영자가 로컬 build 폴더에 직접 넣은 파일에만 해당하며 외부 입력이 닿는 경로가 아니다. README에 검사 범위를 적었다.
- `historical_input.py`의 안내 문구: 가리키는 문서가 이 PR의 트리에는 있다.

### 새 실행 결과

위 "검증"의 수치는 당시 결과로 그대로 둔다. 아래는 이 덧붙임을 뺀 수정이 모두 커밋된 상태에서 같은 명령·환경으로 새로 실행한 것이다.

| 실행 | DB와 계정 | 결과 |
| --- | --- | --- |
| 전체 build | 새 빈 DB `ct_pr_cde_e_3`, 계정 `catchtable` | exit 0. JUnit XML 직접 집계 107클래스 1,204건, 실패 0·오류 0·건너뜀 0. 로그의 PASSED 1,204줄, 8개 task 모두 실행. Flyway 38행 success, checksum 일치 |
| `WaitingSseOperatorPrivilegeTest`만 | 새 빈 DB `ct_pr_cde_e_4`, 계정 `ct_pr_cde` | exit 0. 1클래스 1건 통과. Flyway 38행 success |

`git diff --check`, 바뀐 Markdown의 상대 링크, 바뀐 Python 파일의 구문 검사를 통과했다. CI·프런트 연동·배포는 이 로컬 검증에 없다.
