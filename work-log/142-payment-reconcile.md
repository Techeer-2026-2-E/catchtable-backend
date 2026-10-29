# 기능63 — 결제 결과 재확인

- 작업일: 2026-10-29
- 관련 이슈: [#142](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/142)
- 계약: [기능58 결제 상태 관리 계약](../docs/frontend/feature-58-payment-status.md)의 "결과 재확인 (기능63)"
- 기준: 팀원 [#145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145) 브랜치 `c3f9710` 위(V20·V24 포함), Java 21, PostgreSQL 16 (로컬 Compose)

## 배경과 목표

응답 유실·콜백 지연·처리 중단 뒤 Toss 원장을 서버에서 다시 조회해 결제·예약·선점 상태를 맞추고 보정 이력을 남긴다. 성공 결제와 유효한 승인 시작 보호가 함께 있을 때만 예약을 확정하고, 보호 없는 늦은 승인은 확정 대신 환불 대상으로 분리한다. 재확인을 반복해도 결과·이력·알림은 같아야 한다.

## 판단과 근거

구현 전에 명세·문서·코드 차이를 표로 정리해 사용자에게 확인받았다 (2026-10-29).

| 항목 | 결정 | 근거 |
| --- | --- | --- |
| 팀원 코드 변경 | `Reservation.clearApprovalQuarantine()` 추가, `ReservationPaymentService.settle(paymentId)` 추가, `finish`·`clearVerifiedFailure`의 확정·해제 분기를 private 메서드로 공유 | 격리 해제 메서드가 없고 기존 분기는 요청 키(`Attempt`)가 필요함. 확정·해제 로직 복사 방지. 기존 confirm 동작은 그대로 |
| 늦은 승인의 자리 | 보호·격리를 풀고 기존 `releaseHold`로 반환 | 격리·보호가 남으면 `expireHolds`가 건너뛰어 자리가 계속 막힘 |
| 환불 필요 저장 | `payment.refund_required_at`·`refund_required_reason`(V25) + 상태 이력(같은 상태, SYSTEM) 1건 | 결제당 표시 1개, 기존 상태 이력 구조 재사용 |
| 미반영 환불 | 요청 15일 안 같은 키 재시도, 지나면 `FAILED` | 지시대로. 새 환불 경로는 열지 않음(4B) |
| 늦은 승인 확정 | 같은 최초 승인 시도의 보호가 있으면 확정 | work-log 65의 결정 5A. `feature-10` 39행의 "결정 대기"는 그 이전 기록 |
| 자동 재확인 대상 | `TIMEOUT`, 승인 시작 기록이 있는 `PENDING_PAYMENT` 예약의 결제, 격리 예약의 승인·불명 결제, 진행 중 환불 | 승인 후 `finish` 전에 서버가 멈춘 `DONE`·`FAILED`도 예약 쪽 반영이 필요 |
| Toss 조회 범위 | `READY`·`TIMEOUT`·진행 중 환불만 | "이미 최종 상태면 Toss 호출 없이". 대시보드 직접 취소는 반영 안 함 |
| Toss 조회 4xx | 상태 유지, 로그 | 결과를 단정할 근거가 아님 |
| 웹훅 | `data.orderId`(또는 최상위 `orderId`, 없으면 `paymentKey`)로 결제만 찾고 재조회. 서명 검증 없음, 항상 200 | [Toss 웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events): `PAYMENT_STATUS_CHANGED`는 `data`에 Payment 객체, `DEPOSIT_CALLBACK`은 최상위 `orderId`. [웹훅 가이드](https://docs.tosspayments.com/guides/v2/webhook): 200 기대, 10초 안 응답, 최대 7회 재전송 |
| 스케줄러 | `toss.secret-key`가 있을 때만 켬 | 테스트·로컬에서 실제 Toss 호출과 다른 테스트 데이터 간섭 방지 |
| `reconcile(paymentId, source)` | `source` 인자 없이 `reconcile(paymentId)` | 이력 출처는 항상 `TOSS_QUERY`(환불 필요는 `SYSTEM`), 호출 경로 구분 컬럼이 없음 |

## 실제 변경

- `V25__add_payment_refund_required.sql`: 환불 필요 컬럼 2개와 CHECK(둘 다 있거나 없음, 승인 이후 상태만).
- `Payment.requireRefund`, `PaymentService.requireRefund`, `PaymentResponse.refundRequired`.
- `PaymentReconcileService`: `sync`(고객), `handleWebhook`, `reconcileTargets`(건별 커밋), `reconcile`(조회 → `applyTossPayment(TOSS_QUERY)` 또는 환불 복구 → 예약 반영 + 환불 필요 표시를 한 트랜잭션).
- `PaymentReconcileScheduler`: `payment.reconcile-interval-ms`(기본 60000), `payment.reconcile-batch-size`(기본 50).
- 컨트롤러: `POST /api/user/payments/{paymentId}/sync`, `POST /api/user/webhooks/payments`.
- 팀원 코드: `Reservation.clearApprovalQuarantine`, `ReservationPaymentService.settle`·`Settlement`·공유 private 메서드, `ReservationRepository.findByIdForUpdate`.
- `PaymentRepository`: `findReconcileTargets`, `findByOrderId`, `findByPaymentKey`, `findReservationIdById`, `existsByReservationIdAndIdNotAndStatusIn`.
- ErrorCode 추가 없음.
- 테스트 지원: 가짜 Toss 조회 응답(`query`, `queries`), `PaymentTestData.protect`·`inFlightRefund`, `deleteStore`에서 승인 시작 기록 정리, 커밋된 확정 알림 카운터.

## 검증

| 확인한 내용 | 실제 명령·환경 | 결과·근거 |
| --- | --- | --- |
| 전체 빌드 | 새 빈 DB `catchtable_test_142`, `./gradlew cleanTest build` | 성공, 510개 실패·건너뜀 0. 실행 후 남은 결제·환불·매장·회원 테스트 데이터 0 |
| 동시성 반복 | 같은 DB, `PaymentReconcileConcurrencyTest` 5회 | 5회 모두 통과 |

추가 테스트 26개(서비스 15, 컨트롤러·웹훅 8, 동시성 3). 가짜 Toss와 고정 Clock만 사용했고 실제 Toss는 호출하지 않았다. 처음 동시성 테스트 3개가 결과 `null`에 대한 AssertJ 단언 오류로 실패해 단언만 고쳤다(구현 변경 없음).

## 남은 점과 후속 작업

- `refundRequired` 결제의 실제 환불 실행과 표시 해제는 팀 결정 4B·#68 이후.
- 계속 결과 불명인 건이 많아지면 대상 쿼리가 id 순 상한이라 뒤 건이 밀린다(`ponytail:` 주석). 마지막 확인 시각 순으로 바꿀 수 있다.
- 웹훅 처리 중 Toss 조회가 10초를 넘으면 Toss가 재전송한다. 재처리는 같은 결과라 안전하지만 비동기 처리는 하지 않았다.
- 실제 Toss 테스트 결제·웹훅 등록·프런트 `sync` 호출·CI·배포는 미확인.
- 머지 순서: #145 다음.

## 2026-10-30 dev 머지 준비

사용자 지시에 따라 승인·CI 통과한 선행 PR145/146/147/150을 dev에 머지한 뒤 이 브랜치에 dev를 정상 merge했다. 기존 결제 재확인 로직과 선행 예약·환불 코드는 유지하며 이력을 재작성하지 않았다.

신규 전용 PostgreSQL16(기본 max_connections100)의 전체 빌드604개 중7개가 DB 연결 고갈로 실패했다. 새 재확인 테스트 context가 이후 웨이팅 테스트까지 연결을 보유한 원인이었으며 PaymentReconcileServiceTest에 AFTER_CLASS context 반환을 추가했다. 운영 설정·결제/예약 로직·테스트 단언은 바꾸지 않았다.

수정 후 새 빈 전용 DB에서 Java21의 `./gradlew clean build --no-daemon --console=plain`은 62클래스604개·실패/오류/건너뜀0 및 bootJar 성공이다. 최초 실패·수정 후 XML/전체 로그·검증 입력 SHA를 로컬 merge 증거 폴더에 보존했다. 이 변경 이후 source head CI를 별도로 확인한 뒤 dev에 머지한다. 실제 Toss·공유 DB 적용·배포는 수행하지 않았다.


### GitHub CI의 테스트 시계 경합 후속

첫 통합 head75d0d2b의 GitHub CI(https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/40903869299)는 604개 중1개가 Clock mock stubbing과 백그라운드 조회 경합으로 실패했다. ZoneRegion을 Instant로 변환하려는 ClassCastException을 로그에서 확인했다. 재실행으로 넘기지 않고 재확인 테스트3개가 같은 시계 원본을 안전하게 갱신하도록 AtomicReference 기반 테스트 Clock을 사용했다. 같은 NOW와 시간 전진·검증 단언은 유지하고 세 클래스 종료 시 context를 반환한다.

운영 Clock·스케줄·서비스 로직·DB 설정은 변경하지 않았다. 새 빈 전용 DB의 전체 clean build는 다시62클래스604개·실패/오류/건너뜀0 및 bootJar 성공이다. 이후 새 source head의 CI를 별도로 확인한다.
