# 기능60 — 전액·부분 환불 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#130](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/130)
- 명세: [기능60](https://app.notion.com/3d548d76fa3281369431cf4bd1c07a00)
- 관련: [기존 기능 이슈 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/service/ReservationService.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/payment/api/paymentApi.ts)

## 2026-10-29 실행 계약 후보 — 최초 기준 고정과 예약 변경 보호

문서 이슈 [#189](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/189), 소스 실행 이슈 [#188](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/188), 상위 [#68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)을 연결한다. **이 절은 별도 Draft 소스 후보의 계약이다. 기준 dev `7f90132bfed65d0dce5b5597f162bf4bac62d6c2`에는 새 실행 endpoint가 없으며 소스는 Draft·미머지 후보이며 기준 dev에 반영되지 않았다.** 문서 머지를 기능 배포로 표시하지 않는다. 아래 날짜별 기록의 실행 보류·미구현 설명은 당시 이력이다. 사용자는 최초 요청 기준 저장과 진행 중 변경 차단을 승인해 #188 범위의 실행 보류를 해제했다.

### 돈만 환불하는 요청

`POST /api/user/payments/{paymentId}/cancel`은 기존 고객 식별 `X-Member-Id`와 필수 `Idempotency-Key`를 받는다. CUSTOMER 유형의 본인 결제 및 시작 전·미입장 CONFIRMED 예약만 새 요청을 시작할 수 있다. 남의 결제는404다.

```json
{"cancelReason":"고객 요청","cancelAmount":1000}
```

`cancelReason`은 필수이고 최대500자다. `cancelAmount`는 양수 정수이며 생략하면 최초 유효 요청 당시의 남은 정책상 환불액 전부다. 명시적0원은 입력 오류이고, 서버 계산 결과가0원이면 `NOT_REQUIRED`로 반환하며 PG를 호출하지 않는다. 이미 전액 환불된 결제에는 새 요청을 시작할 수 없지만 완료된 같은 키의 재조회는 가능하다. 같은 키는 사유와 요청액의 원본을 재사용해야 한다. 금액 생략과 명시적 금액은 서로 다른 요청으로 판단한다.

성공200은 현재 공통 `RefundResult` 계약을 사용한다. 예를 들어 원결제10,000원에서1,000원을 환불하면 다음과 같다. 예약·테이블·예약 상태와 취소 알림은 유지한다.

```json
{
  "paymentId": 10,
  "reservationId": 20,
  "currency": "KRW",
  "refundAmount": 1000,
  "totalRefundedAmount": 1000,
  "remainingAmount": 9000,
  "paymentStatus": "PARTIAL_REFUNDED",
  "refundStatus": "COMPLETED",
  "refundedAt": "2030-01-20T12:00:00+09:00"
}
```

현재 상태값은 `DONE`, `PARTIAL_REFUNDED`, `REFUNDED`다. 과거 초안의 `PAID`나 `PARTIALLY_REFUNDED`를 사용하지 않는다. 계산 결과0원의 `refundStatus`는 `NOT_REQUIRED`, `refundedAt`은 null이다. 완료된 동일 요청은 최초 처리 결과를 반환하고 PG를 다시 호출하지 않는다.

### 예약 취소 요청

- 고객: `POST /api/user/reservations/{reservationId}/cancel`. 기존 `X-Member-Id`와 본문 없음 계약을 유지한다. 새 양수 환불을 실행하려면 `Idempotency-Key`를 보낸다.
- 점주: `POST /api/owner/reservations/{reservationId}/cancel`. 기존 고정 목 점주 `2L`, 필수 본문 `{"reason":"매장 사정"}`를 유지한다. 이 경로는 `X-Member-Id`나 JWT로 점주를 식별하지 않는다. 새 양수 환불에는 `Idempotency-Key`를 보낸다.
- 양수 환불을 실행하는 취소는 남은 정책상 환불액 **전부가 성공한 뒤에** 기존 예약 응답으로 취소를 확정하고 자리 반환·취소 SSE를 한 번 반영한다. 무료·추가 환불0원 요청은 기존 헤더 없는 호출도 가능하다. 헤더 없는 양수 미환불 요청은 기존409 `REFUND_NOT_COMPLETED`를 유지한다.
- 고객의 새 취소는 시작 전·미입장 CONFIRMED만 가능하다. 점주의 기존 미입장 CONFIRMED 취소는 시작 후에도 가능하다. 최초 유효 요청 후 지연된 동일 요청 복구는 저장된 취소 의도를 사용한다. 완료된 취소의 같은 키 재시도는 반환된 자리를 다시 건드리거나 환불을 추가하지 않는다.

### 최초 기준과 진행 중 화면

최초 **유효** 요청에서 요청시각, 당시 변경된 예약시각, 정책상 총 환불한도, 요청 원본 금액과 실제 환불액을 DB에 보관한다. 금액·정책은 최초 예약 스냅샷을 사용하고 원 미만을 버린 뒤 누적 완료 환불액을 차감한다. 고객은 당시 예약의 KST 방문일, 점주는 날짜와 무관한 원결제100%를 총 한도로 삼는다. 날짜 변경이나 프로세스 재시작 후에도 동일 요청의 기준을 다시 계산하지 않는다.

예약 쪽 준비 기록이 커밋된 직후부터 공통 `PaymentRefund`의 `REQUESTED`/`UNKNOWN`이 해소될 때까지 시간·인원 변경, 입장, 노쇼, 자동 이용완료, 다른 환불·취소를 차단한다. 신규 실행 기록이 없는 기존 공통 진행 중 환불도 같은 보호를 받는다. 외부 호출 중에는 예약·결제 행 잠금과 바깥 트랜잭션을 유지하지 않는다.

실패·결과 불명 응답을 취소 완료 화면으로 해석하지 않는다. 예약·자리·취소 알림은 유지한다. 확정 실패면 진행 보호를 해제하고 같은 실패 키는 기존 실패를 반환한다. `UNKNOWN`은 로컬 임의 시간 만료로 해제하지 않는다. 기존 공통 결과 재확인 및 같은 키 재시도를 사용한다. `POST /api/user/payments/{paymentId}/sync` 또는 기존 공통 재확인에서 환불 완료를 확인하면 저장된 예약 취소 의도도 한 번 정산한다. 돈만 환불한 요청은 재확인 후에도 예약을 취소하지 않는다.

| HTTP / code | 후보 계약에서의 의미 |
| --- | --- |
| 400 `INVALID_INPUT` | 빈/과도한 키·사유, 양수가 아닌 명시적 금액 등 입력 오류. 최초 요청 기록과 PG 호출 없음. |
| 403 `FORBIDDEN_MEMBER_TYPE` | 돈 환불 endpoint를 CUSTOMER가 아닌 유형으로 호출함. |
| 404 `PAYMENT_NOT_FOUND` / `RESERVATION_NOT_FOUND` | 본인 결제·해당 고객/점주 예약을 찾을 수 없음. |
| 409 `REFUND_DATA_NOT_READY` | 최초 금액·정책·원장 불일치 또는 진행 중인 다른 환불. 예약을 유지한다. |
| 409 `REFUND_AMOUNT_EXCEEDED` | 요청액이 최초 정책상 한도에서 누적 완료액을 뺀 잔액을 초과함. |
| 409 `IDEMPOTENCY_CONFLICT` | 같은 키의 금액 생략 여부·금액·사유·돈 환불/취소 의도가 달라짐. |
| 409 `PAYMENT_RECONCILIATION_REQUIRED` | 결과 불명. 같은 요청/기존 sync로 결과를 확인한다. |
| 409 `PAYMENT_REFUND_FAILED` / `PAYMENT_NOT_REFUNDABLE` | 확정 환불 거절 또는 새 환불을 시작할 수 없는 결제 상태. |

이 후보는 실제 PostgreSQL과 가짜/루프백 클라이언트로 검증하며 실제 Toss 환불, 프런트 코드·화면, JWT, 배포를 확인한 결과가 아니다. 공유 V20 적용 이력 UNKNOWN과 V20 없는 populated V22 upgrade 차단은 남아 있다. 기능33 예약금 설정과 prepare도 별도 담당 범위다. 이 문서 후보 자체는 링크·Markdown·실제 DTO/컨트롤러와의 대조 및 `git diff --check`만 검사한다.

## 목적과 현재 범위

환불 계산의 확정된 판단과 실제 외부 취소 실행의 미구현 경계를 정리한다. 이 문서는 환불 API를 추가하지 않는다.

## 연동 계약과 확인 사항

- 점주 취소는 시점과 관계없이 원결제액의 100%를 총 환불 한도로 사용한다. 이미 환불한 금액은 다시 지급하지 않는다.
- 고객 취소는 최초 금액·정책 종류를 사용하되, 예약이 변경됐다면 변경 후 방문일을 환불 비율의 날짜 기준으로 사용한다.
- 추가 환불액은 max(0, 원결제액 × 정책 비율의 원 미만 버림 − 기환불액)이다. 10,000원·50%·기환불 1,000원이면 추가액은 4,000원이다.
- 전액·부분·0원 결과를 구분하며 0원은 외부 취소를 호출하지 않는 명세다. 누적 환불액이 원결제액을 넘거나 같은 요청으로 이중 환불돼서는 안 된다.
- POST /api/user/payments/{paymentId}/cancel과 예약 취소의 환불 연결은 예정 범위이며 현재 구현되지 않았다. 계산기만 있다고 외부 환불이 실행된 것으로 표시하지 않는다.

## 남은 연결

#68에서 실제 취소 실행·결과 저장·응답 유실 복구를 공통 결제 상태·멱등성·결과 재확인 계약과 맞춘다. 기능61 계산과 환불 실행의 완료 범위를 구분한다.

## 2026-10-29 재확인 후속 검토 메모

기준 dev `7da9c1db77dac1327b565e8d1f36bd3961ea57c6`와 별도 미머지 후보를 구분한다. 기존 취소 보호 [PR #150](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/150)은 Draft이고, 공통 결과 재확인 [PR #179](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/179)와 별도 [기능60 통합 테스트 #181](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/181)은 후속 검토 범위다. #181은 기존 보호를 검증하는 테스트이며 새 환불 실행을 구현하지 않는다.

REQUESTED/UNKNOWN의 기존 환불 결과를 조회하는 동안 추가 환불 예상액이0원이어도 예상액·고객/점주 취소는 `409 REFUND_DATA_NOT_READY`이며 예약·자리·SSE를 보존한다. 검증된 기존 환불 결과가 반영되어도 돈 환불만으로 예약을 취소하지 않는다. 이후 예약 취소 요청이 허용되는 경우에만 자리와 알림을 한 번 변경한다. 취소 완료 뒤 결제를 재확인해도 예약을 다시 확정하거나 환불 필요 표식을 새로 만들지 않는다.

양수 환불 실행·새 PG 취소 endpoint는 사용자4B에 따라 계속 보류한다. 상위 [#68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)은 열어 두며 [#67](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/67)은 예상액 보호 확인의 관련 범위다. 공유 V20 적용 이력과 기존 DB upgrade, 실제 Toss·프런트/JWT·배포는 미확인이다. 이 문서 후보는 링크·기준 소스·Markdown·diff만 검사하며 앱 테스트 증거는 별도 소스 후보와 구분한다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.

## 2026-10-28 후속 — 환불 확인 전 취소 보호 계약

위 2026-10-27 기록은 당시 상태로 보존한다. 이번 계약의 구현 기준은 기능60 브랜치 [a000485](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/a000485fee9c3970fb3fb55b57ddd07b26c98689)와 [작업 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/a000485fee9c3970fb3fb55b57ddd07b26c98689/work-log/68-refund-before-cancellation.md)이며, 기능61 [PR #146](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/146)·스냅샷 [PR #145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145)이 선행한다. 공통 결제 상태·클라이언트 [PR #144](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/144)는 dev에 머지되었지만, 확인 기준 dev `34c6d7b`에 이 취소 보호 코드는 아직 없다. 문서 반영을 API 배포로 간주하지 않는다.

사용자는 외부 환불 성공 확인 또는 환불액 0원 결정 후에만 예약 취소·자리 반환을 반영하고, 실패·결과 불명에는 기존 예약·자리를 유지하도록 결정했다. 현재 구현은 이 원칙을 기존 고객·점주 취소 경로에 적용하는 경계까지다. 양수 환불 요청 실행은 아직 제공하지 않는다.

### 요청과 응답

- 고객: `POST /api/user/reservations/{reservationId}/cancel`, 기존 `X-Member-Id`와 요청 본문 없음 계약을 유지한다.
- 점주: `POST /api/owner/reservations/{reservationId}/cancel`, 기존 고정 목 점주 `2L`과 필수 취소 사유 `{"reason":"매장 사정"}` 요청 본문 계약을 유지한다. 이 API는 아직 `X-Member-Id` 기반 인증이 아니며 이번 작업에서 회원 식별 방식을 바꾸지 않았다.
- 성공 응답은 기존 예약 응답이다. 무료, 정책상 추가 환불0원, 필요한 환불이 공통 원장에 확인된 경우에만 기존 취소를 확정한다. 재요청으로 취소 알림이 중복 발행되지 않는다.
- 고객은 최초 금액·정책과 변경 후 방문일을, 점주는 날짜와 상관없이 최초 금액100%를 기준으로 기환불액을 차감한다. 무료·0원·이미 필요한 환불 완료 모두 이 경로에서 외부 취소를 새로 호출하지 않는다.
- 클라이언트의 환불액 입력이나 예상액 조회 성공으로 서버 검사를 생략하지 않는다. [기능61 조회](feature-61-refund-preview.md)는 계산 시점의 예상액이며 실행 성공이 아니다.

| HTTP / code | 의미와 화면 처리 |
| --- | --- |
| 409 `REFUND_NOT_COMPLETED` | 추가 양수 환불이 남아 취소를 확정하지 않음. 취소·환불 완료 화면으로 이동하거나 예약을 목록에서 제거하지 않는다. |
| 409 `REFUND_DATA_NOT_READY` | 유료 최초 금액·정책·승인 원장·누적 환불액을 확인할 수 없거나 READY/TIMEOUT 상태가 있음. 0원 또는 확정 실패로 바꾸지 않고 오류를 표시한다. |
| 기존 401/403/404/409 | 회원 식별, 고객/점주 소유권, 예약 상태·시각 검사를 그대로 유지한다. |

두 신규 환불 관련409에서는 예약 상태·취소시각·자리 반환이 롤백되고 취소 SSE도 발생하지 않는다. 이미 취소된 예약의 재요청은 기존 결과를 유지하며, 과거에 누락된 환불을 새로 복구하는 기능은 아니다.

### 현재 프런트와 후속 연결

프런트 dev `e47b63b2c546dd5ff3d3b327b0299c5453b3342d`의 [고객 호출부](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/reservation/api/reservationApi.ts)와 [상세 화면](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/pages/reservation-detail/ReservationDetailPage.tsx)을 대조했다. 기존 취소 요청에서 이 오류를 받아 예약을 보존해야 하며, 예상액 조회 오류를 무료 취소로 해석하면 안 된다. 이 문서에서는 프런트 코드를 변경하지 않았다.

`POST /api/user/payments/{paymentId}/cancel`은 계속 미구현이다. [기능62 #141](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/141)의 요청 멱등성 및 [기능63 #142](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/142)의 결과 재확인 계약 후 양수 환불을 연결한다. 현재 TIMEOUT은 승인 전 결과 불명을 나타내며 승인 후 환불 진행/결과 불명을 별도로 구별할 계약이 없다. 실제 외부 환불 경로를 열기 전 이 표식을 취소 보호 검사에도 연결해야 한다.

이 문서는 기능60 전체 완료·실제 PG 환불·화면 연동·배포 확인을 뜻하지 않는다. 구현 브랜치는 전용 PostgreSQL16에서 전체462개 테스트와 신규 경계21개가 통과했지만, 이 문서 커밋 자체는 링크·소스·Markdown·`git diff --check`만 검사했다.

## 2026-10-29 후속 — 기능62 환불 표식과 취소 보호

앞선 날짜의 기록은 보존한다. 확인 기준 dev `555e0aabd243b52e5007e55460d2aae398952f27`에는 [공통 기능62 환불 서비스](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/555e0aabd243b52e5007e55460d2aae398952f27/src/main/java/com/catchtable/payment/service/PaymentRefundService.java)와 [영속 환불 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/555e0aabd243b52e5007e55460d2aae398952f27/src/main/java/com/catchtable/payment/entity/PaymentRefund.java)이 있다. 따라서 과거의 “환불 진행·결과 불명을 구별할 계약이 없다”는 현재 공통 모델 설명으로 사용하지 않는다. 기능60 [Draft PR #150](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/150)의 공개 head `a000485`에는 새 기록을 보는 보강이 아직 없다.

로컬 후속 커밋 `665c120`의 `ReservationRefundService.calculate`는 `REQUESTED`·`UNKNOWN` 환불이 있으면 기존 `409 REFUND_DATA_NOT_READY`로 예상액과 고객·점주 취소를 막는다. 정책상 추가액이0이어도 확정 잔액처럼 다루지 않고 예약·자리·취소 SSE를 보존한다. 이 커밋은 공개 PR·dev 반영 전 후보이며, 기존 공개 head의 검증 결과와 구분한다.

공통 호출은 [기능58 문서의 기능62 절](feature-58-payment-status.md#중복-요청-방지-기능62)을 따른다. `PaymentRefundService.refund`는 바깥 트랜잭션 없이 호출하고 마지막 인자에는 **정책상 누적 환불 한도**를 넘긴다. 추가 환불액을 그 자리에 넘기면 기환불액이 두 번 차감된다. 점주 취소는 매장 소유권을 확인한 뒤 원결제 고객 식별자로 호출해야 한다. 클라이언트가 정책·actor·회원 ID를 본문으로 정하지 않는다.

기존 공개 및 이번 로컬 취소 경로는 양수 환불이 남으면 여전히 `409 REFUND_NOT_COMPLETED`다. `POST /api/user/payments/{paymentId}/cancel` HTTP 실행 endpoint는 제공하지 않는다. 결제 환불과 예약 취소의 결합 방식, 기존 취소의 키 계약, 최초 요청시각 고정 및 처리 중 변경 동결은 사용자 답 대기다. 이미 결정한 순서는 외부 환불 성공 확인 또는 추가0원 뒤 취소·자리 반환이며, 실패·결과 불명에는 기존 예약·자리를 유지하는 것이다.

같은 키의 금액·사유·처리 당시 결과는 공통 서비스가 보존한다. 결과 불명에 새 키를 만들어 다시 보내지 않는다. Toss 멱등키15일 경과 또는 남은 `REQUESTED`·`UNKNOWN`의 복구는 [기능63 #142](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/142)와 연결해야 하며 이번 보강은 재확인·복구 구현이 아니다.

로컬 소스는 전용 PostgreSQL16의 관련53개 테스트(실패·건너뜀0)로 확인했다. 이 문서 커밋은 코드·링크·Markdown·`git diff --check`만 확인하며 새 CI·실제 Toss·프런트 화면·배포는 미확인이다.

## 2026-10-29 실제 결정 반영 — 실행 보류와 기존 취소 보호

앞선 사용자 답 대기 문구는 당시 기록이다. 실제 답변2A는 결제 환불 경로가 **돈만 환불하고 예약을 유지**하며 예약 취소는 남은 **정책상** 환불액을 처리한 뒤 취소한다는 계약이다. 고객의 임의 전액 환불 허용이 아니다. 점주는 원결제100%에서 기환불액을 차감한다. 3A의 client `Idempotency-Key`는 양수 환불을 실행하는 예약 취소에만 필요한 미래 계약이며 기존 무료 취소 요청은 유지한다.

4B에 따라 최초 유효 요청시각·한도 저장과 처리 중 일정/인원 변경·입장·노쇼·다른 취소 동결이 합의될 때까지 양수 환불 endpoint와 PG 기반 예약 취소는 **구현·공개하지 않는다**. `POST /api/user/payments/{paymentId}/cancel`은 없다. [후속 소스](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/610539b5faa18395df89a379937028033bffe41b/src/main/java/com/catchtable/reservation/service/ReservationRefundService.java)와 [고객 취소](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/610539b5faa18395df89a379937028033bffe41b/src/main/java/com/catchtable/reservation/service/ReservationService.java)는 기존 무료·추가0원·필요 환불 확인 후 취소만 유지한다. 양수가 남으면 `409 REFUND_NOT_COMPLETED`, REQUESTED/UNKNOWN 또는 불신 원장은 `409 REFUND_DATA_NOT_READY`이며 예약·자리·SSE를 보존한다. 고객/점주 route·기존 식별/사유 계약은 위 절과 같다.

5A의 [기능10 승인 확정](feature-10-deposit-payment.md)은 환불 실행 허용을 뜻하지 않는다. 공통 서비스 재사용·기능142 복구도 이 경계를 넘지 않는다. 기능 [Draft PR #150](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/150)은 미머지 검토용이며 V20 공유 이력·populated V22 upgrade 차단은 그대로다. 기능60 전체 완료·실제 환불·화면·배포로 표시하지 않는다. 이 문서 변경 자체는 고정 소스·링크·Markdown·diff만 검사한다.

## 2026-10-29 후속 — 기능63 환불 복구

앞선 기록은 보존한다. 남은 `REQUESTED`·`UNKNOWN`은 [기능63 결과 재확인](feature-58-payment-status.md#결과-재확인-기능63)이 Toss 누적 취소액으로 반영 여부를 확인한다. 반영됐으면 `COMPLETED`, 아니면 요청 15일 안에는 같은 키 재시도, 지나면 `FAILED`(`IDEMPOTENCY_KEY_EXPIRED`)다. 늦은 승인은 `refundRequired` 표시만 하며 양수 환불 endpoint는 4B 결정 전까지 열지 않는다.
