# 기능58 — 결제 상태 관리

- 작업일: 2026-10-28
- 관련 이슈: [#140](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/140)
- 계약: [기능58 결제 상태 관리 계약](../docs/frontend/feature-58-payment-status.md)
- 기준: `dev@cc95faf`, Java 21, PostgreSQL 16 (로컬 Compose), 프런트 `dev@e47b63b`.

## 배경과 목표

#65·#68·기능62·63이 공통으로 쓸 결제 모델이 없었다. 결제 시도마다 현재 상태 하나와 변경 이력을 남기고, 허용된 전이만 수행하는 기준을 만든다. 결제 성공은 Toss 승인 응답·조회 결과로만 반영한다.

## 판단과 근거

2026-10-28 사용자 결정:

- 상태는 명세 7개(`READY`, `DONE`, `FAILED`, `CANCELED`, `TIMEOUT`, `PARTIAL_REFUNDED`, `REFUNDED`)로 한다. 프런트 5개와의 차이는 계약 문서에 정리했다.
- 선점과 예약은 같은 `reservation` 행이므로 FK는 `reservation_id` 하나로 둔다. 응답의 `holdId`는 항상 이 id이고, `reservationId`는 승인 이후에만 같은 id다.
- 누적 환불액은 DB·엔티티에서 `refunded_amount`, 응답에서 프런트 이름 `canceledAmount`를 쓴다.
- Toss `ABORTED`는 `FAILED`, `EXPIRED`는 `CANCELED`, `CANCELED`는 `REFUNDED`, `PARTIAL_CANCELED`는 `PARTIAL_REFUNDED`로 변환한다. 응답 시간초과·연결 끊김, Toss 5xx, `409 IDEMPOTENT_REQUEST_PROCESSING`은 결과 불명으로 분류한다.
- 상태 변경 서비스는 `applyTossPayment` 하나만 둔다. confirm·재확인·취소 응답을 같은 방식으로 반영하며, HTTP API는 만들지 않는다.

기본값으로 정한 것: 변경 출처는 `USER`·`TOSS_CONFIRM`·`TOSS_QUERY`·`TOSS_CANCEL`·`SYSTEM`이다. `refund`는 누적 총액을 받아, Toss `totalAmount - balanceAmount`를 그대로 넘겨도 멱등이 되게 했다. 예약당 진행 중·성공 결제는 부분 유니크 인덱스로 1건만 허용한다.

확인한 차이: 작업 지시의 "예약금·환불 정책 스냅샷 컬럼"은 dev에 없다. 결제 금액은 팩토리 인자로 받는다. ErrorCode에는 원래 구역 주석이 없어서, 마지막에 `// 결제` 구역을 새로 추가했다. Notion 정본은 이 작업 환경에서 열리지 않아 대조하지 못했다.

## 실제 변경

- `V19__create_payment.sql`: `payment`, `payment_status_history`. `order_id`·`payment_key` 유니크와 금액·상태·출처 체크 제약을 둔다. 승인 이후 상태에서는 승인 정보를 필수로 하고, 상태별 누적 환불액 범위를 검사한다. 예약당 진행 중 결제 부분 유니크 인덱스도 추가했다. V18은 #109가 사용한다.
- `payment` 패키지: 엔티티·상태 전이, Toss RestClient 구현(`TossPaymentsConfig`에서 타임아웃 설정), `PaymentService`(조회, `applyTossPayment`), `GET /api/user/payments`, `GET /api/user/payments/{paymentId}`.
- `ErrorCode`: `PAYMENT_NOT_FOUND`, `INVALID_PAYMENT_STATUS`, `REFUND_AMOUNT_EXCEEDED`, `PAYMENT_MISMATCH`.
- `application.yml`: `toss.secret-key: ${TOSS_SECRET_KEY:}`, `toss.base-url`, `toss.connect-timeout`, `toss.read-timeout`. 시크릿 값은 저장소에 없다.

## 검증

| 확인한 내용 | 실제 명령 또는 방법·환경 | 결과·근거 |
| --- | --- | --- |
| 허용·거부 전이, 같은 요청 멱등, 누적 환불액 한도·감소 거부 | `PaymentTest` | 39개 통과 |
| Toss 경로·Basic 인증·멱등키 헤더·본문, 4xx 실패, 시간초과·5xx·처리 중 결과 불명, 상태 변환 | `RestTossPaymentsClientTest` (MockRestServiceServer, 실제 호출 없음) | 14개 통과 |
| 상태 변경 시 이력 1건, 재요청 시 이력 없음, TIMEOUT 후 환불 반영, 주문·금액 불일치 거부 | `PaymentServiceTest` (`@SpringBootTest` + `@Transactional`) | 7개 통과 |
| 목록·상세 200, 남의 결제 404, 점주 403, 헤더 없음 401 (`$.code`) | `UserPaymentControllerTest` | 5개 통과 |
| 전체 빌드 | `DB_HOST=localhost DB_PORT=5433 DB_NAME=catchtable_test_140 DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew build` | 성공, 387개 실패·건너뜀 0 |

`catchtable_test_140`은 이번에 새로 만든 빈 테스트 DB다. 기존 `catchtable_test`에는 #109의 V18이 적용되어 있어 이 브랜치의 Flyway 검증과 맞지 않으므로 쓰지 않았다. CI·실제 Toss 테스트 결제·프런트 연동은 확인하지 않았다.

## 남은 점과 후속 작업

- 예약 상태 연결(#65·#68), 선점 자동 만료가 `READY`·`TIMEOUT` 결제를 보도록 하는 연결(기능63), 멱등키 정책(기능62)이 남았다. 내용은 계약 문서의 "남은 연결"에 있다.
- 프런트 `PaymentStatus` 타입 갱신이 필요하다.
- Notion API 명세에 상태값·응답 필드를 반영해야 한다(외부 문서는 수정하지 않았다).
