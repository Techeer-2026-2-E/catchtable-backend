# 기능58 — 결제 상태 관리 계약

- 확인일: 2026-10-28
- 기능 이슈: [#140](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/140)
- 관련: [#65 예약금 가결제](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [#68 환불 실행](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68), [기능10 연동](feature-10-deposit-payment.md), [기능60 연동](feature-60-refund-execution.md)
- 대조한 코드: [백엔드 결제 패키지](../../src/main/java/com/catchtable/payment), [프런트 dev e47b63b model.ts](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/entities/payment/model.ts), [paymentApi.ts](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/payment/api/paymentApi.ts), [Toss 코어 API](https://docs.tosspayments.com/reference)

## 목적과 현재 범위

결제 시도마다 하나의 현재 상태와 상태 변경 이력을 남기는 공통 결제 모델, Toss 클라이언트, 고객 결제 조회 API를 제공한다. 승인·환불 중복 요청 방지(기능62, [#141](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/141))는 서비스까지 구현했다. 결과 재확인·웹훅·자동 재확인(기능63, [#142](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/142))은 아래 "결과 재확인"에 있다. prepare·cancel HTTP API와 양수 환불 실행 연결(#68)은 포함하지 않는다.

## 상태값과 허용 전이

| 상태 | 의미 |
| --- | --- |
| `READY` | 결제 대기 (주문 생성 후 승인 전) |
| `DONE` | 승인 완료 |
| `FAILED` | 승인 실패 |
| `CANCELED` | 승인 전 취소·이탈·결제 유효시간 만료 |
| `TIMEOUT` | 결과 불명, 조회로 재확인할 대상 |
| `PARTIAL_REFUNDED` | 부분 환불 |
| `REFUNDED` | 전액 환불 |

- `READY` → `DONE`, `FAILED`, `CANCELED`, `TIMEOUT`
- `TIMEOUT` → `DONE`, `FAILED`, `CANCELED`
- `DONE` → `PARTIAL_REFUNDED`, `REFUNDED`
- `PARTIAL_REFUNDED` → `PARTIAL_REFUNDED`(누적 환불액 증가), `REFUNDED`
- 그 밖의 변경은 `409 INVALID_PAYMENT_STATUS`. 같은 상태·같은 값의 재요청은 변경·이력 없이 성공한다.
- 누적 환불액은 결제 금액을 넘을 수 없다(`409 REFUND_AMOUNT_EXCEEDED`). DB 제약도 상태별 누적 환불액 범위를 검사한다.
- 진행 중·성공 결제(`READY`, `TIMEOUT`, `DONE`, `PARTIAL_REFUNDED`)는 예약(선점)당 1건이다. 실패·취소된 결제 뒤에는 새 결제를 만들 수 있다.

## 조회 API

- `GET /api/user/payments?page=0&size=10`: 내 결제 목록, 최신순. 리뷰 목록과 같은 형식 `{ payments, page, size, hasNext }`. `page` 0 이상(기본 0), `size` 1~50(기본 10), 범위 밖은 `400 INVALID_INPUT`.
- `GET /api/user/payments/{paymentId}`: 상세. 목록 항목 필드에 `refunds`(요청순)를 더한다.
- 모두 `X-Member-Id` 헤더. 없으면 `401 UNAUTHORIZED`, 고객이 아니면 `403 FORBIDDEN_MEMBER_TYPE`, 남의 결제나 없는 결제는 `404 PAYMENT_NOT_FOUND`.
- 목록 항목: `paymentId`, `reservationId`, `holdId`, `orderId`, `orderName`, `amount`, `status`, `method`, `approvedAt`, `canceledAmount`, `receiptUrl`, `refundRequired`(예약 확정 없이 확인된 승인, 환불 대상. 2026-10-29 추가).
- `refunds` 항목: `refundId`, `refundAmount`, `status`(`REQUESTED`, `COMPLETED`, `NOT_REQUIRED`, `FAILED`, `UNKNOWN`), `cancelReason`, `refundedAt`(확정 전 `null`).
- `receiptNotice`: 상세에만 있는 테스트 결제 안내 문구(고정값).
- 2026-10-28 변경: 목록이 배열에서 페이징 객체로 바뀌었다. 프런트 `paymentApi.ts`의 목록 응답 타입 갱신이 필요하다.

## 프런트와 다른 점

| 항목 | 프런트 dev | 백엔드 | 프런트 조치 |
| --- | --- | --- | --- |
| 상태값 | `READY`, `DONE`, `CANCELED`, `PARTIAL_CANCELED`, `FAILED` | 위 7개 | `PARTIAL_CANCELED` → `PARTIAL_REFUNDED`, `TIMEOUT`·`REFUNDED` 추가 |
| `CANCELED` 의미 | 정의 없음 (Toss에서는 승인 후 전액 취소) | 승인 전 취소·이탈만 | 승인 후 전액 취소는 `REFUNDED`로 표시 |
| `TIMEOUT` | 없음 | 결과 불명 | 실패로 표시하지 말고 "결제 확인 중"으로 표시 |
| `reservationId` / `holdId` | 별도 값 | 선점과 예약이 같은 행이라 같은 id. `holdId`는 항상, `reservationId`는 승인 이후(`DONE`·`PARTIAL_REFUNDED`·`REFUNDED`)에만 값이 있고 그 전에는 `null` | 타입 그대로 사용 |
| `canceledAmount` | 취소 금액 | 누적 환불액 (DB `refunded_amount`) | 이름 그대로 사용 |
| `method` | `string \| null` | Toss 값 그대로 (예: `카드`, `간편결제`), 승인 전 `null` | 없음 |

## #65·#68·기능62·63이 쓸 계약

엔티티 메서드 (`Payment`). 변경되면 `PaymentStatusHistory`를 반환하고, 같은 요청 반복이면 `null`이다. 반환된 이력을 저장한다.

| 메서드 | 전이 | 멱등 기준 |
| --- | --- | --- |
| `Payment.ready(member, reservation, orderId, orderName, amount)` | 생성 → `READY` | — |
| `approve(paymentKey, method, approvedAt, receiptUrl, source, now)` | `READY`·`TIMEOUT` → `DONE` | 이미 승인 이후 상태이고 같은 `paymentKey` |
| `fail(code, message, source, now)` | `READY`·`TIMEOUT` → `FAILED` | 이미 `FAILED`이고 같은 코드 |
| `cancel(reason, source, now)` | `READY`·`TIMEOUT` → `CANCELED` | 이미 `CANCELED` |
| `markTimeout(reason, source, now)` | `READY` → `TIMEOUT` | 이미 `TIMEOUT` |
| `refund(totalRefundedAmount, reason, source, now)` | `DONE`·`PARTIAL_REFUNDED` → `PARTIAL_REFUNDED`·`REFUNDED` | 같은 누적 환불액. 이번 환불액이 아닌 **누적 총액**을 넘긴다 |

- 변경 출처 `PaymentStatusSource`: `USER`, `TOSS_CONFIRM`, `TOSS_QUERY`, `TOSS_CANCEL`, `SYSTEM`.
- 상태 변경은 엔티티 메서드를 직접 부르지 말고 `PaymentService`의 잠금 메서드(`applyTossPayment`, `markFailed`, `markTimeout`)로 한다. 모두 결제 행을 잠근 뒤 현재 상태를 확인하고 바꾸며, 같은 요청 반복이면 이력 없이 성공한다.
- `PaymentService.applyTossPayment(paymentId, tossResponse, source)`: 결제 행을 잠그고, 주문번호·금액이 다르면 `409 PAYMENT_MISMATCH`로 거부한다. 그 뒤 Toss 상태에 맞는 엔티티 메서드를 호출해 이력을 저장한다. `TIMEOUT` 중 Toss에서 환불까지 진행된 결과면 승인·환불 이력을 차례로 남긴다. confirm 응답, 재확인 조회, 취소 응답에 모두 사용한다.
- `PaymentService.markFailed(paymentId, code, message, source)`: Toss 승인 4xx(`TossPaymentsException`)일 때 `FAILED`로 바꾼다. 승인 이후 상태면 `409 INVALID_PAYMENT_STATUS`.
- `PaymentService.markTimeout(paymentId, reason, source)`: 결과 불명(`TossPaymentsUnknownResultException`)일 때 `TIMEOUT`으로 바꾼다. 이후 조회 결과를 `applyTossPayment`로 반영한다.
- prepare 재요청 시 같은 선점에 `READY` 결제가 있으면 새로 만들지 않고 기존 결제(같은 `orderId`)를 반환한다. 예약당 진행 중 결제 1건 인덱스(`uk_payment_active_reservation`) 때문에 새 `READY`를 만들면 DB 유니크 위반이 된다.
- 리다이렉트의 `paymentKey`·`orderId`·`amount`만으로 `approve`를 호출하지 않는다. Toss 승인 응답 또는 조회 결과를 `applyTossPayment`에 넘긴다.

Toss 클라이언트 (`TossPaymentsClient`, Toss v1, Basic 인증 `시크릿키:`):

| 메서드 | Toss API |
| --- | --- |
| `confirm(paymentKey, orderId, amount, idempotencyKey)` | `POST /v1/payments/confirm` |
| `getByPaymentKey(paymentKey)` | `GET /v1/payments/{paymentKey}` |
| `getByOrderId(orderId)` | `GET /v1/payments/orders/{orderId}` (API 개별 연동 키 `test_sk`만 가능) |
| `cancel(paymentKey, cancelAmount, cancelReason, idempotencyKey)` | `POST /v1/payments/{paymentKey}/cancel`, `cancelAmount`가 `null`이면 전액 |

- 멱등키는 `Idempotency-Key` 헤더로만 전달하고 `null`이면 생략한다. 키 생성·저장 정책은 기능62에서 정한다(Toss 최대 300자, 15일 유효).
- `TossPaymentsException`(실패 확정): Toss 4xx. `httpStatus`, `code`, `message`를 담는다.
- `TossPaymentsUnknownResultException`(결과 불명): 응답 시간초과·연결 끊김, Toss 5xx, `409 IDEMPOTENT_REQUEST_PROCESSING`. 실패로 처리하지 말고 `markTimeout` 후 조회로 재확인한다.
- Toss 상태 변환: `READY`·`IN_PROGRESS`·`WAITING_FOR_DEPOSIT` → `READY`, `DONE` → `DONE`, `ABORTED` → `FAILED`, `EXPIRED` → `CANCELED`, `PARTIAL_CANCELED` → `PARTIAL_REFUNDED`, `CANCELED` → `REFUNDED`.
- 설정: `toss.secret-key`(환경변수 `TOSS_SECRET_KEY`, 기본값 없음), `toss.base-url`(기본 `https://api.tosspayments.com`), `toss.connect-timeout` 5초, `toss.read-timeout` 30초.

## 중복 요청 방지 (기능62)

Toss 호출 중에는 DB 행 잠금을 잡지 않는다. Toss 중복 처리는 `Idempotency-Key`로 막고, 결과는 결제 행 잠금 안에서 반영한다. 두 서비스는 트랜잭션 없이 단계별로 커밋하므로, 결과 불명 기록은 `409` 응답 전에 저장된다. 트랜잭션 안에서 호출하면 Toss를 부르기 전에 `IllegalStateException`이 난다. 호출하는 컨트롤러·서비스 메서드에 `@Transactional`을 붙이지 않는다.

**승인** `PaymentApprovalService.approve(memberId, orderId, paymentKey, amount)`: #65 confirm 컨트롤러가 호출한다. `PaymentResponse`를 반환한다.

| 상황 | 결과 |
| --- | --- |
| 남의 주문·없는 주문 | `404 PAYMENT_NOT_FOUND` |
| 금액 불일치 | `409 PAYMENT_MISMATCH` |
| 이미 승인 이후 + 같은 `paymentKey` | Toss 호출 없이 현재 결과 |
| 이미 승인 이후 + 다른 `paymentKey` | `409 PAYMENT_MISMATCH` |
| `FAILED`·`CANCELED` | `409 INVALID_PAYMENT_STATUS` |
| 다른 결제에 쓰인 `paymentKey` (사전 검사·DB 유니크) | `409 PAYMENT_KEY_ALREADY_USED` |
| Toss 성공 | `applyTossPayment(TOSS_CONFIRM)` 결과 |
| Toss 4xx | `markFailed` 후 `status: FAILED` 응답. 같은 요청 재시도는 `409 INVALID_PAYMENT_STATUS` |
| 결과 불명 | `markTimeout` 커밋 후 `409 PAYMENT_RECONCILIATION_REQUIRED`. 같은 요청 재시도는 같은 키로 Toss 재승인 |
| 동시 요청이 먼저 승인 | 4xx·결과 불명이어도 현재 승인 결과 |

- 승인 멱등키: `"confirm-" + orderId` (주문당 1개).
- Toss 승인 거절은 예외가 아니라 `FAILED` 결제를 200으로 반환한다. #65 confirm 컨트롤러는 반환된 `status`를 확인해 `DONE`일 때만 예약을 확정하고, `FAILED`면 예약을 확정하지 않고 그대로 실패 응답으로 내려준다(프런트는 `status`로 실패를 표시). `READY`(Toss 처리 중)도 확정하지 않는다.

**환불** `PaymentRefundService.refund(memberId, paymentId, idempotencyKey, cancelReason, cancelAmount, policyRefundLimit)`: #68 cancel 컨트롤러가 `Idempotency-Key` 헤더와 본문 `cancelReason`, `cancelAmount`(선택)를 받아 호출한다. `policyRefundLimit`은 기능61 계산 결과(정책상 누적 환불 한도)다. `RefundResult`를 그대로 200으로 반환한다.

- 응답: `paymentId`, `reservationId`, `currency`(`KRW`), `refundAmount`, `totalRefundedAmount`, `remainingAmount`, `paymentStatus`, `refundStatus`(`COMPLETED`·`NOT_REQUIRED`), `refundedAt`(`NOT_REQUIRED`는 `null`). 값은 처리 당시 기준이다.
- 입력: 키 필수·300자 이하(Toss 제한), 사유 필수·공백 제거 후 500자 이하, 금액은 생략 또는 양수. 어기면 `400 INVALID_INPUT`. 키는 결제별로 구분한다(`payment_id`, `idempotency_key` 유니크).
- 키는 Toss 취소 요청의 `Idempotency-Key`로 그대로 전달되므로, 다른 결제와 겹치지 않게 프런트가 환불 요청마다 UUID로 만들고 재시도할 때는 같은 값을 다시 보낸다.
- 허용액 = `min(policyRefundLimit, 결제 금액) - 누적 환불액`. 결제 행 잠금 안에서 계산한다. 금액을 생략하면 허용액 전부, 지정한 금액이 허용액을 넘으면 `409 REFUND_AMOUNT_EXCEEDED`.
- 허용액 0원 + 금액 생략: Toss 호출 없이 `NOT_REQUIRED`, `refundAmount: 0`.
- 같은 키 + 같은 요청(금액·사유): `COMPLETED`·`NOT_REQUIRED`는 최초 결과, `FAILED`는 같은 오류, `REQUESTED`·`UNKNOWN`은 저장된 금액으로 같은 키 Toss 재시도. 재시도 때 받은 `policyRefundLimit`은 쓰지 않는다.
- 같은 키 + 다른 금액·사유: `409 IDEMPOTENCY_CONFLICT`.
- 새 키: 같은 결제에 `REQUESTED`·`UNKNOWN` 환불이 있으면 `409 PAYMENT_RECONCILIATION_REQUIRED`(DB 부분 유니크 인덱스도 1건만 허용). `DONE`·`PARTIAL_REFUNDED`가 아니면 `409 PAYMENT_NOT_REFUNDABLE`.
- Toss 성공 후에만 `applyTossPayment(TOSS_CANCEL)`로 누적 환불액·결제 상태를 확정한다. Toss 4xx는 `FAILED` 저장 후 `409 PAYMENT_REFUND_FAILED`. 결과 불명은 `UNKNOWN` 저장 후 `409 PAYMENT_RECONCILIATION_REQUIRED`.
- 남의 결제·없는 결제: `404 PAYMENT_NOT_FOUND`. 승인·환불 서비스는 회원 유형을 따로 검사하지 않는다(점주는 본인 결제가 없어 404).
- Toss 멱등키는 15일간 유효하다. 15일이 지난 `UNKNOWN`을 같은 키로 재시도하면 Toss가 새 요청으로 처리할 수 있으므로, 기능63 재확인(조회)을 먼저 해야 한다. 기능63에서 조회 후 15일 안에만 재시도하도록 구현했다.

Notion "결제 취소·환불" P2 초안과 다른 점:

| 항목 | 초안 | 구현 | 조치 |
| --- | --- | --- | --- |
| `paymentStatus` 값 | `PAID`, `PARTIALLY_REFUNDED` (결제 상태 담당과 매핑할 값) | 기능58 이름 `DONE`, `PARTIAL_REFUNDED`, `REFUNDED` | Notion 수정 요청 |
| 승인 결과 불명 | 환불만 `PAYMENT_RECONCILIATION_REQUIRED` | 승인도 같은 코드 | Notion에 승인 응답 추가 요청 |
| 환불 Toss 거절 | 오류 목록에 없음 | `409 PAYMENT_REFUND_FAILED`(Toss 환불 거절). 같은 키 재요청도 같은 오류 | Notion 오류 목록에 추가 요청 |
| 결제 내역 | 페이징 (형식 미정) | `{ payments, page, size, hasNext }` | 프런트 타입 갱신 |
| 결제 상세 취소 이력 | 필드 미정 | `refunds` | 프런트 타입 갱신 |

## 결과 재확인 (기능63)

응답 유실·콜백 지연·처리 중단 뒤 Toss 원장을 서버에서 다시 조회해 결제·예약·선점을 맞춘다. 리다이렉트 성공 화면이나 웹훅 본문만으로 상태를 바꾸지 않는다. 재확인을 반복해도 결과·이력·알림은 같다.

- `POST /api/user/payments/{paymentId}/sync`: 고객 재확인. `X-Member-Id` 필수, 응답은 결제 상세(`GET /{paymentId}`와 같은 형식). 없으면 `401 UNAUTHORIZED`, 고객이 아니면 `403 FORBIDDEN_MEMBER_TYPE`, 남의 결제는 `404 PAYMENT_NOT_FOUND`(Toss 호출 없음). Toss 조회가 실패하면 상태를 그대로 두고 200으로 현재 상세를 반환한다(`TIMEOUT`이면 아직 불명).
- `POST /api/user/webhooks/payments`: Toss 웹훅. 본문에서 `orderId`(없으면 `paymentKey`)만 꺼내 결제를 찾고 Toss를 다시 조회한다. `PAYMENT_STATUS_CHANGED`는 `data.orderId`, 가상계좌 `DEPOSIT_CALLBACK`은 최상위 `orderId`. 서명 검증은 하지 않는다. 모르는 결제·잘못된 본문·처리 실패도 200(재전송 방지), 실패는 로그만 남긴다.
- 자동 재확인: `payment.reconcile-interval-ms`(기본 60000) 간격, 한 번에 `payment.reconcile-batch-size`(기본 50)건, 건별 별도 커밋. 대상은 `TIMEOUT` 결제, 승인 시작 기록이 있는 `PENDING_PAYMENT` 예약의 결제, 격리 예약의 승인·불명 결제, `REQUESTED`·`UNKNOWN` 환불. `toss.secret-key`가 비어 있으면(로컬·테스트) 스케줄러가 꺼진다.
- Toss 조회는 `READY`·`TIMEOUT` 결제와 진행 중 환불에만 한다. 그 외 최종 상태는 Toss를 부르지 않고 예약 쪽만 맞춘다. Toss 대시보드에서 직접 한 취소는 반영하지 않는다.
- 트랜잭션 밖에서만 호출한다(안이면 `IllegalStateException`). Toss 조회 중 DB 잠금 없음, 반영은 `applyTossPayment(TOSS_QUERY)`와 예약→결제 행 잠금.

예약 쪽 반영 (#145 승인 시작 보호 기준):

| Toss 결과 | 예약 상태 | 처리 |
| --- | --- | --- |
| 승인(`DONE`) | 같은 최초 승인 시도의 보호 있음 | 예약 확정 1회(`RESERVATION_CONFIRMED` 알림 1회). 원래 선점 기한이 지났어도 확정(결정 5A) |
| 승인 | 보호 없음·격리·확정된 적 없음 | 확정하지 않음. 보호·격리를 풀고 자리 반환(`EXPIRED`/`CANCELLED`). 결제는 `refundRequired=true` + 상태 이력 "환불 필요" 1건 |
| 실패(`ABORTED`)·만료(`EXPIRED`) | 보호·격리 있음 | 결제 `FAILED`·`CANCELED`, 보호·격리 해제 후 자리 반환 |
| 승인 전(`READY`·`IN_PROGRESS`)·조회 실패 | — | 그대로 유지. 선점 기한이 지나도 만료하지 않음(다음 재확인 대상) |

- 늦은 승인을 자동 환불하지 않는다. 양수 환불 실행은 팀 결정(4B) 전까지 열지 않고, `refundRequired`로 대상만 표시한다.
- 승인 시작 15일이 지난 미확정 건도 재승인하지 않고 조회 결과로만 정리한다.
- 진행 중 환불(`REQUESTED`·`UNKNOWN`): Toss 누적 취소액이 기존 누적액 + 이번 환불액 이상이면 `COMPLETED`로 확정한다. 아니면 요청 15일 안에는 같은 멱등키로 재시도(기능62 같은 키 경로), 지나면 `FAILED`(`IDEMPOTENCY_KEY_EXPIRED`)로 둔다.

## 남은 연결

- 예약 상태와 결제 상태 연결(#65·#68): 결제 승인·환불을 트랜잭션 밖에서 먼저 호출하고, 그 결과로 예약 확정·취소를 별도 트랜잭션에서 처리한다. 외부 결제와 예약 변경은 한 트랜잭션으로 묶을 수 없다. 승인 후 예약 확정이 실패한 결제는 기능63 재확인이 `refundRequired`로 표시한다(2026-10-29).
- 선점 만료는 #145의 승인 시작 보호로 `READY`·`TIMEOUT` 승인 진행 건을 만료하지 않는다(2026-10-29, 기능63 테스트로 동시 실행 확인).
- cancel HTTP 컨트롤러와 `refundRequired` 결제의 실제 환불 실행은 #68·팀 결정 4B 뒤에 연결한다. 환불 서비스(기능62)는 기능63 재시도 외에 호출하는 곳이 없다.
- 예약금·환불 정책 스냅샷은 #145(V20)에서 추가한다. 결제 금액은 `Payment.ready` 인자로 받는다.

## 문서 검증

결제 패키지의 코드·테스트, 프런트 dev `e47b63b`의 두 파일, Toss 공식 문서(코어 API, 인증·멱등키, 요청·응답)를 대조했다. 격리 DB에서 `./gradlew build`를 통과했다. 프런트 수정·화면 연동·실제 Toss 호출·배포는 확인하지 않았다.
