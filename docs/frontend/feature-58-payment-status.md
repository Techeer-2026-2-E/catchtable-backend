# 기능58 — 결제 상태 관리 계약

- 확인일: 2026-10-28
- 기능 이슈: [#140](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/140)
- 관련: [#65 예약금 가결제](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [#68 환불 실행](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68), [기능10 연동](feature-10-deposit-payment.md), [기능60 연동](feature-60-refund-execution.md)
- 대조한 코드: [백엔드 결제 패키지](../../src/main/java/com/catchtable/payment), [프런트 dev e47b63b model.ts](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/entities/payment/model.ts), [paymentApi.ts](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/payment/api/paymentApi.ts), [Toss 코어 API](https://docs.tosspayments.com/reference)

## 목적과 현재 범위

결제 시도마다 하나의 현재 상태와 상태 변경 이력을 남기는 공통 결제 모델, Toss 클라이언트, 고객 결제 조회 API를 제공한다. prepare·confirm·cancel API, 예약 확정·환불 연결(#65·#68), 멱등키 정책(기능62), 결과 재확인·웹훅(기능63)은 포함하지 않는다.

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

- `GET /api/user/payments`: 내 결제 목록, 최신순. `GET /api/user/payments/{paymentId}`: 상세.
- 모두 `X-Member-Id` 헤더. 없으면 `401 UNAUTHORIZED`, 고객이 아니면 `403 FORBIDDEN_MEMBER_TYPE`, 남의 결제나 없는 결제는 `404 PAYMENT_NOT_FOUND`.
- 응답: `paymentId`, `reservationId`, `holdId`, `orderId`, `orderName`, `amount`, `status`, `method`, `approvedAt`, `canceledAmount`, `receiptUrl`.

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
- `PaymentService.applyTossPayment(paymentId, tossResponse, source)`: 결제 행을 잠그고, 주문번호·금액이 다르면 `409 PAYMENT_MISMATCH`로 거부한다. 그 뒤 Toss 상태에 맞는 엔티티 메서드를 호출해 이력을 저장한다. `TIMEOUT` 중 Toss에서 환불까지 진행된 결과면 승인·환불 이력을 차례로 남긴다. confirm 응답, 재확인 조회, 취소 응답에 모두 사용한다.
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

## 남은 연결

- 예약 상태와 결제 상태를 맞추는 일(`DONE` ↔ 예약 `CONFIRMED`, 환불 ↔ 예약 취소)은 #65·#68에서 같은 트랜잭션으로 연결한다. 이번 변경은 결제 측 규칙(예약당 진행 중 결제 1건, 승인·환불 금액 일관성)만 보장한다.
- 선점 자동 만료(`expireHolds`)는 아직 결제 상태를 보지 않는다. `READY`·`TIMEOUT` 결제가 있는 선점을 만료·반환하지 않도록 기능63과 함께 연결해야 한다.
- 예약금·환불 정책 스냅샷 컬럼은 아직 없다. 결제 금액은 `Payment.ready` 인자로 받는다.

## 문서 검증

결제 패키지의 코드·테스트, 프런트 dev `e47b63b`의 두 파일, Toss 공식 문서(코어 API, 인증·멱등키, 요청·응답)를 대조했다. 격리 DB에서 `./gradlew build`를 통과했다. 프런트 수정·화면 연동·실제 Toss 호출·배포는 확인하지 않았다.
