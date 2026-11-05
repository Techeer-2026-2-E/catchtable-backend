# 예약 미확정 승인 결제의 전액 환불

2026-11-05 사용자 결정으로 추가한 계약이다. 기존 기능53~56의 선점·승인 보호와 일반 예약 취소의 정책 환불을 보존한다. [배경·구현·검증](../../work-log/2026-11-05-hold-integration.md#2026-11-05-추가--정책-고지와-미확정-승인-환불)을 참조한다. Notion의 과거 API 목록에 두 경로가 이미 등재됐다고 간주하지 않는다.

## 결제 전 환불 기준

`POST /api/user/payments/prepare`는 기존10필드에 다음4필드를 추가한다. 요청은 계속 `{"holdId":123}`뿐이다. 고객의 금액·정책 입력을 받지 않는다.

| 필드 | 의미 |
| --- | --- |
| `refundPolicy` | 최초 선점에 저장한 `FLEXIBLE`/`STANDARD`/`STRICT` |
| `refundPolicyName` | `유연`/`일반`/`엄격` 표시명 |
| `refundRules` | `minDaysBefore`, `refundRate`의 내림차순 배열. 해당 예약일 전 일수 이상인 첫 규칙 적용 |
| `refundPolicyNotice` | Asia/Seoul 예약일 기준·점주 취소 전액·노쇼 미환불 안내 |

기존 고객 취소 비율은 유연(1일 이상100%, 당일50%), 일반(2일 이상100%, 1일50%, 당일0%), 엄격(3일 이상100%, 2일50%, 1일20%, 당일0%)이다. 계산과 고지는 같은 서버 규칙을 사용한다. 이후 매장 설정 변경으로 기존 선점의 금액·정책·만료시각을 바꾸지 않는다. 프런트는 정책 고지가 없으면 새 결제를 시작하지 않고 주문 재확인을 안내한다. 취소 시 실제 예상액은 기존 `refund-preview`를 다시 조회한다.

## 고객의 전액 환불 요청

두 API 모두 `X-Member-Id`로 고객 본인을 확인한다. JWT 적용으로 간주하지 않는다.

| 요청 | 결과 |
| --- | --- |
| `POST /api/user/payments/{paymentId}/unconfirmed-refund` + 필수 `Idempotency-Key` | 신규202, 같은 요청 재조회200. 본문·고객 지정 금액 없음 |
| `GET /api/user/payments/{paymentId}/unconfirmed-refund` | 최신 요청200, 요청 없음204 |

서버는 본인 결제의 `refundRequired=true`, 결제 `DONE`/`PARTIAL_REFUNDED`, 예약 `EXPIRED`/`CANCELLED`이며 확정·입장 이력이 없는 건만 받는다. 승인 보호·격리, 진행 환불, 양수 최초 스냅샷과 결제의 불일치는 거절한다. **원결제액에서 이미 환불한 금액을 뺀 잔액 전부**가 요청액이다. 일반 고객 취소 수수료를 적용하지 않는다. 확정된 예약의 취소·돈만 환불 API를 이 경로로 대체하지 않는다.

공통 응답은 다음 형식이다. ID와 시각은 가상 예시다.

```json
{
  "requestId": 42,
  "paymentId": 31,
  "reservationId": 21,
  "currency": "KRW",
  "refundAmount": 10001,
  "refundStatus": "REQUESTED",
  "requestedAt": "2030-01-20T12:00:00+09:00",
  "completedAt": null
}
```

| 상태 | 화면과 재시도 |
| --- | --- |
| `REQUESTED` | 접수 안내. 같은 키를 보존하고 최신 요청 조회/polling |
| `UNKNOWN` | 결과 확인 중. 새 요청·새 키를 만들지 않고 기존 `POST /payments/{id}/sync`와 요청 조회 사용 |
| `COMPLETED` | 완료 안내·결제 상세 갱신. 보관한 키 제거 |
| `FAILED` | 동결 해제. 같은 실패 키는409 `PAYMENT_REFUND_FAILED`이고 PG 재호출 없음. 사용자가 재요청 확인 시 새 키 생성 가능 |

접수 응답이 유실되면 GET으로 저장된 요청부터 확인한다. 조회 실패 상태에서는 새 환불 버튼을 막는다. 멱등키는 브라우저 새로고침에도 유지한다. 기존/다른 요청과 같은 키의 의미가 다르면409 `IDEMPOTENCY_CONFLICT`다. 일반 환불과 함께 REQUESTED/UNKNOWN이면 기존 변경 동결을 유지한다.

## 커밋 뒤 처리와 복구

V33의 `reservation_refund_outbox`를 추가한다. 예약 행→해당 결제 행 잠금 순서 안에서 환불 의도(`UNCONFIRMED_REFUND`)·동결·아웃박스를 원자적으로 저장한다. 롤백이면 외부 환불도 발생하지 않는다.

워커는 `FOR UPDATE SKIP LOCKED`로120초 lease를 획득하고 DB 잠금을 해제한 뒤 기존 공통 환불 서비스를 호출한다. 재시도와 lease 만료의 중복 처리는 동일 멱등키·기존 요청 원본과 시도 번호를 사용한다. 공통 요청 저장 후 중단됐으면 기존 재확인·15일 경계를 재사용한다. 공통 요청이 아직 없으면10초부터 최대300초까지 지연 재시도한다.

아웃박스 `DELIVERED`는 공통 환불 저장소로의 인계 완료다. PG 환불 완료와 다르며 `REQUESTED`/`UNKNOWN`은 기존 공통 재확인 스케줄러·sync 대상에 남는다. 공통 자동 재확인은 기존과 같이 Toss 시크릿 키가 설정된 환경에서 동작한다. 결제 완료 표시는 `refundStatus=COMPLETED`를 확인한다. 외부 효과는 PG 멱등성에 의존하므로 워커의 한 번 실행을 보장한다고 설명하지 않는다.

예약은 이미 종결됐으므로 환불 완료 때 기존 예약의 상태·재고·취소 이력·SSE를 새로 만들지 않는다. 같은 슬롯의 새 선점도 변경하지 않는다. 스케줄러 기본값은 `reservation.refund-outbox.scheduling-enabled=true`, `poll-interval-ms=1000`, `initial-delay-ms=1000`이다. 테스트의 기본 비활성 설정과 실제 스케줄러 전용 검증은 구분한다.

## 적용 순서와 한계

백엔드 V33/API → 프런트 순서다. V33은 기존31개 SQL(V32까지)을 고치지 않고 새 테이블과 허용 intent를 추가한다. 롤백 시 이미 접수한 환불과 PG 효과가 사라지지 않으며 이전 코드가 새 intent를 읽을 수 있는지도 고려해야 한다. 진행 요청을 남긴 채 단순히 이전 앱만 띄우지 않는다.

Playwright의 실제 Spring 앱·일회용 PostgreSQL16 검증은 fake PG와 별도 영속 fake 원장으로 수행했다. 실제 Toss 정상 승인·양수 환불·실제 PG 장애/재시작 복구·공유 DB 적용·배포는 아직 검증하지 않았다.
