# 기능62 — 결제 중복 요청 방지

- 작업일: 2026-10-28
- 관련 이슈: [#141](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/141)
- 계약: [기능58 결제 상태 관리 계약](../docs/frontend/feature-58-payment-status.md)의 "중복 요청 방지"
- 기준: `dev@657291d`(#144 머지), Java 21, PostgreSQL 16 (로컬 Compose). 팀원 [#145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145)(V20 예약금 스냅샷)와 겹치는 코드 없음.

## 배경과 목표

같은 예약의 승인·환불 요청이 재시도·동시 요청으로 두 번 처리되지 않게 한다. 같은 요청 식별자로 재시도하면 같은 결과를 반환하고, 예약에는 유효 결제 1건과 요청별 환불 결과 1건만 남긴다. 결과 불명은 실패로 단정하지 않는다. 컨트롤러는 #65·#68 담당이라 서비스까지만 구현한다. 웹훅 중복은 기능63 범위다.

## 판단과 근거

사용자 결정(2026-10-28):

- `paymentStatus`는 기능58 이름(`DONE`, `PARTIAL_REFUNDED`, `REFUNDED`)을 쓴다. Notion 초안의 `PAID`·`PARTIALLY_REFUNDED`는 "결제 상태 담당과 매핑할 값"이라 결제 담당 기준으로 맞추고 차이를 계약 문서에 기록했다.
- 정책 계산(기능61)은 팀원 담당이다. 환불 서비스는 정책상 누적 환불 한도 `policyRefundLimit`을 인자로 받고, 결제 행 잠금 안에서 `min(한도, 결제 금액) - 누적 환불액`으로 허용액을 계산한다.
- Toss 호출 중에는 DB 행 잠금을 잡지 않는다. 중복 호출은 Toss `Idempotency-Key`로 막고, 결과 반영은 기존 잠금 메서드로 한다.
- 승인 Toss 4xx: `markFailed` 후 `status: FAILED`를 반환한다. 같은 요청 재시도는 `409 INVALID_PAYMENT_STATUS`다. 첫 응답과 재시도 응답이 다르다는 점을 확인하고 지시대로 유지했다.
- 환불 Toss 4xx: 새 오류코드 `PAYMENT_REFUND_FAILED`(409)를 쓴다. 같은 키 재요청도 같은 오류다.
- 같은 키 환불이 `REQUESTED`이면 같은 키로 Toss를 다시 호출한다. 동시 같은 키 요청은 Toss 호출이 두 번 생길 수 있지만 실제 환불은 Toss 멱등키로 1회만 처리된다. 테스트도 이 기준(실제 처리 1회·누적액 1회 반영)으로 확인했다.

기본값으로 정한 것:

- 승인·환불 서비스는 회원 유형을 검사하지 않는다. 초안 오류 목록에 403이 없고, 점주는 본인 결제가 없어 404가 된다.
- 허용액 0원에 금액을 지정하면 `REFUND_AMOUNT_EXCEEDED`, `REFUNDED` 결제에 새 키는 `PAYMENT_NOT_REFUNDABLE`이다.
- 상세 `refunds`에는 상태와 무관하게 모든 환불 기록을 요청순으로 넣는다.
- 환불 응답의 `paymentStatus`·누적액은 처리 당시 값이다. 같은 키 재요청은 이후 다른 환불이 있어도 최초 결과를 반환한다.
- 결제별 처리 중 환불 1건은 결제 행 잠금 + DB 부분 유니크 인덱스로 이중 보장한다.

## 실제 변경

- `V21__create_payment_refund.sql`: `payment_refund`. `(payment_id, idempotency_key)` 유니크, 상태·금액·확정 시각 체크 제약, 결제별 `REQUESTED`·`UNKNOWN` 1건 부분 유니크 인덱스.
- `PaymentRefund`·`PaymentRefundStatus`: 요청 원본(금액 nullable, 사유), 확정 환불액, 처리 후 누적액, 실패 코드·메시지, 요청·확정 시각. 확정·실패·결과 불명은 처리 중일 때만 바뀐다.
- `PaymentApprovalService.approve`: 사전 검사(본인 주문, 금액, 승인 재요청, 종료 상태, 다른 결제의 `paymentKey`) → Toss confirm(`"confirm-" + orderId`) → `applyTossPayment`·`markFailed`·`markTimeout`. 트랜잭션 없이 각 단계가 커밋한다. 동시 요청이 먼저 승인했으면 현재 결과를 반환한다.
- `PaymentRefundService.refund`: 입력 검증 → 결제 행 잠금 안에서 재요청 판단·허용액 계산·`REQUESTED` 저장(커밋) → Toss cancel(같은 키) → 잠금 안에서 `applyTossPayment(TOSS_CANCEL)`+`COMPLETED`, `FAILED`, `UNKNOWN`. 단계별 트랜잭션은 `TransactionTemplate`으로 나눴다.
- 조회: `GET /api/user/payments`를 `page`·`size` + `Slice` 형식 `{ payments, page, size, hasNext }`로 바꿨다. 상세에 `refunds`를 추가했다(`PaymentDetailResponse`, `@JsonUnwrapped`로 기존 필드 유지).
- `ErrorCode`: `PAYMENT_KEY_ALREADY_USED`, `IDEMPOTENCY_CONFLICT`, `PAYMENT_RECONCILIATION_REQUIRED`, `PAYMENT_NOT_REFUNDABLE`, `PAYMENT_REFUND_FAILED`.

## 검증

| 확인한 내용 | 실제 명령 또는 방법·환경 | 결과·근거 |
| --- | --- | --- |
| 승인: 트랜잭션 안 호출 거부(Toss 미호출), 재요청 Toss 1회, 다른 키·금액 거부, 다른 결제의 키 거부, 결과 불명 → TIMEOUT·409 후 같은 키 재승인, 4xx → FAILED, 남의 주문 404 | `PaymentApprovalServiceTest` (가짜 Toss, 트랜잭션 없음) | 7개 통과 |
| 환불: 트랜잭션 안 호출 거부(Toss 미호출·기록 없음), 같은 키 최초 결과, 키 충돌 409, 입력 400, 금액 생략·초과, 0원 NOT_REQUIRED, 결과 불명 뒤 다른 키 409·같은 키 확정, 4xx, 부분 환불 누적, 환불 불가 상태, 남의 결제 404 | `PaymentRefundServiceTest` (가짜 Toss, 트랜잭션 없음) | 12개 통과 |
| 동시성: 같은 주문 승인 → DONE·이력 1건, 같은 키 환불 → 실제 처리 1회, 다른 키 환불 → 누적액 한도 이내, 결과 불명 기록이 409 전에 커밋 | `PaymentIdempotencyConcurrencyTest` (트랜잭션 없음, finally 정리) | 4개 통과, 5회 반복 모두 통과·남은 데이터 0 |
| 결제 내역 페이징·크기 검증, 상세 `refunds` | `UserPaymentControllerTest` | 7개 통과 |
| 전체 빌드 | 새 빈 DB `catchtable_test_141`, `./gradlew cleanTest build` | 성공, 415개 실패·건너뜀 0. 실행 후 남은 결제·환불·매장 데이터 0 |

V20이 없는 DB에서 V19 → V21 순서로 적용했다. CI·실제 Toss 호출·프런트 연동은 확인하지 않았다.

## 검토 반영 — 2026-10-28

- 트랜잭션 밖 호출 강제: `approve`·`refund` 시작 시 `TransactionSynchronizationManager.isActualTransactionActive()`면 `IllegalStateException("결제 승인·환불은 트랜잭션 밖에서 호출")`. 바깥 트랜잭션에 묶이면 결과 불명 기록이 409와 함께 롤백되고, Toss 호출 중 결제 행 잠금이 유지되기 때문이다.
- 승인·환불 서비스 테스트를 `@Transactional`에서 트랜잭션 없는 방식으로 바꿨다. UUID 데이터를 매 테스트 후 매장 단위로 지운다(`PaymentTestData.deleteStore`, 동시성 테스트도 같은 정리 사용). DB에서 다시 읽은 시각은 오프셋이 다를 수 있어 같은 결과 비교는 시점 기준으로 한다.
- 계약 문서: 예약 연결은 "결제 먼저(트랜잭션 밖) → 예약 확정·취소는 별도 트랜잭션"으로 고쳤다. 승인 후 예약 확정이 실패하면 결제는 환불 대상으로 남기고 기능63에서 처리한다. 승인 거절은 `FAILED` 결제(200) 반환이며 #65 컨트롤러는 `DONE`일 때만 예약을 확정한다고 적었다. 환불 Toss 거절 `409 PAYMENT_REFUND_FAILED`는 초안과 다른 점 표에 이미 있어 문구만 보강했다.

## 남은 점과 후속 작업

- 머지 순서: #145(V20)가 먼저 머지돼야 한다. V21 적용 뒤 V20이 들어오면 Flyway 검증이 실패한다.
- 호출부 없음: `approve`·`refund`는 #65 confirm·#68 cancel 컨트롤러가 트랜잭션 밖에서 연결한다. 승인 후 예약 확정 실패분의 환불은 기능63에서 처리한다.
- 기능63: `TIMEOUT`·`UNKNOWN`·서버 중단으로 남은 `REQUESTED`의 재확인, 웹훅 중복 처리. Toss 멱등키 15일이 지난 같은 키 재시도는 재확인 후에만 해야 한다.
- 프런트: 결제 목록 응답 형식과 상세 `refunds` 타입 갱신.
- Notion: 환불 명세의 `paymentStatus` 값, 승인 결과 불명 응답, `PAYMENT_REFUND_FAILED` 추가 요청(외부 문서는 수정하지 않았다).
