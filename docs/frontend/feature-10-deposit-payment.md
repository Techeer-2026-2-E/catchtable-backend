# 기능10 — 예약금 가결제 프런트 연동

## 2026-10-29 현재 계약 — 저장된 유료 선점의 결제 준비

- 문서 이슈: [#196](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/196), 기능 이슈: [#195](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/195), 상위 [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65)
- 정본: [기능10](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b), [prepare API](https://app.notion.com/p/0ba48d76fa328309ab3a018b1b5fed0f), [confirm API](https://app.notion.com/p/39b48d76fa328319a4a181bc187e8970)
- 문서 기준 dev: `9da5d70d0afa296dad25fc689413c04c76a89272`. 해당 dev 문서의 이전 기록을 보존하고 준비 API의 현행 후보 계약을 추가했다.
- 구현 후보: [Draft PR #198](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/198), head `f81bcaa95de0244d76ff4a2d40e9d89165d06c55`. 검증한 앱·테스트 기준은 `3080466a18aa18d6f7fb334771b80273b6731c1b`이며 이후 커밋은 work-log만 추가·정정해 앱·테스트·빌드 입력 트리는 같다. 소스는 **dev 대상 Draft·미머지**다. 문서 머지는 dev의 API 제공·소스 머지·배포 완료를 뜻하지 않는다. [해당 소스 head CI](https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/40826793631)는 SUCCESS다.

### 대상과 선행 조건

`POST /api/user/payments/prepare`는 신뢰할 수 있는 서버 경로가 이미 저장한 유료 선점만 대상으로 한다. 양수 `depositAmountSnapshot`, 유효한 `refundPolicySnapshot`, 미입장 `PENDING_PAYMENT`, 원래 `requestedAt + 5분` 만료시각과 그 시각 전이라는 조건이 필요하다. 기능33의 매장 설정과 실제 유료 선점 공개 생성은 연결되지 않았다. 현재 공개 무료 선점은 스냅샷이 없으므로 prepare로 유료 전환되지 않는다. 현재 매장 설정이나 임의 기본 금액·정책으로 과거 원본을 채우지 않는다. 아래 10000원은 예시이며 기본값이 아니다.

요청은 `Content-Type: application/json`, 숫자 `X-Member-Id`와 CUSTOMER 회원으로 처리한다. JWT 연동 완료를 뜻하지 않는다. 본인 선점만 허용하고 다른 고객 선점은 없는 선점과 같은 404다.

```http
POST /api/user/payments/prepare
Content-Type: application/json
X-Member-Id: 101

{"holdId":202}
```

`holdId`는 양수 정수이며 현재 모델에서 `reservationId`와 같은 값이다. 본문에는 이 필드만 정의되어 있다. 금액·정책·orderId·expiresAt·회원 정보를 추가하더라도 기존 Jackson 규칙으로 무시되어 서버 원본을 덮어쓰지 못한다. 인원수로 금액을 곱하지 않는다.

### 성공과 재요청

새 주문은 **201**, 같은 유효 선점의 기존 READY 재사용은 **200**이다. 응답은 같은 최상위 형식이며 `payment` 래퍼가 없다.

```json
{
  "paymentId": 303,
  "reservationId": 202,
  "holdId": 202,
  "orderId": "ct-00000000-0000-4000-8000-000000000000",
  "orderName": "예약금",
  "amount": 10000,
  "status": "READY",
  "expiresAt": "2030-01-20T03:04:00Z",
  "serverTime": "2030-01-20T12:00:00+09:00",
  "remainingSeconds": 240
}
```

서버는 64자 이내의 유일한 orderId(`ct-` + UUID, 39자), 100자 이내 주문명, 최초 스냅샷 금액을 저장한다. 준비 응답의 reservationId/holdId는 같은 선점 ID다. 승인 전 reservationId를 null로 내리는 기존 결제 조회의 PaymentResponse와 준비 DTO를 혼동하지 않는다.

재요청·동시 요청은 같은 READY의 paymentId·orderId·주문명·최초 금액·원래 만료시각을 그대로 반환한다. 새 주문이나 최초 이력을 추가하지 않고 결제/예약 행도 갱신하지 않는다. 새 주문의 최초 이력은 `READY → READY`, `USER`, `결제 준비` 1건이다. 준비는 SSE와 외부 PG 호출을 만들지 않는다.

expiresAt/serverTime은 오프셋이 포함된 같은 시간축으로 비교한다. DB에서 읽은 expiresAt은 예시처럼 UTC일 수 있다. remainingSeconds는 서버 시각 기준 남은 초를 내림하고 0 이상으로 제한한다. 만료 1µs 전에는 0초와 READY가 반환될 수 있지만 원래 만료 정각부터 신규·재사용 모두 거절된다. 조회나 준비로 5분을 연장하지 않는다.

### 충돌과 오류

| HTTP / code | 의미와 프런트 처리 |
| --- | --- |
| 400 `INVALID_INPUT` | holdId 누락·null·0·음수·잘못된 JSON/타입 |
| 401 `UNAUTHORIZED` | X-Member-Id 누락·잘못된 숫자 |
| 403 `FORBIDDEN_MEMBER_TYPE` | CUSTOMER가 아닌 회원 |
| 404 `MEMBER_NOT_FOUND` | 존재하지 않는 회원 |
| 404 `RESERVATION_NOT_FOUND` | 없는 선점 또는 다른 고객의 선점 |
| 409 `PAYMENT_MISMATCH` | 무료/누락/잘못된 스냅샷·바뀐 5분 원본·회원/금액/승인 전 바인딩이 맞지 않는 READY·여러 결제 행. 서버가 원본을 추정하거나 복구하지 않음 |
| 409 `INVALID_PAYMENT_STATUS` | 만료·미래 요청시각·입장·PENDING_PAYMENT 이외 예약 또는 기존 FAILED/CANCELED. 새 재결제 정책 없음 |
| 409 `PAYMENT_STATUS_CHECK_REQUIRED` | 승인 시작·격리·TIMEOUT·DONE·PARTIAL_REFUNDED·REFUNDED. **기존 결제 상태를 조회**하고 새 주문·추가 결제를 만들지 않음 |
| 409 `REFUND_DATA_NOT_READY` | 기존 환불 동결. 동결을 풀거나 새 주문을 만들지 않음 |

승인 진행·결과 불명·완료의 409 메시지는 “진행하거나 완료한 결제가 있습니다. 기존 결제 상태를 조회해 주세요.”다. 알고 있는 paymentId로 `GET /api/user/payments/{paymentId}`를 확인한다. ID를 잃었다면 `GET /api/user/payments`로 조회하며, 결과 불명에 대한 기존 `POST /api/user/payments/{paymentId}/sync`와 재확인 계약을 따른다. prepare는 기존 승인 키·상태·격리 정보를 지우거나 PG를 재호출하지 않는다. 여러 모순된 결제 행은 우선 PAYMENT_MISMATCH로 거절한다.

### 승인·재확인·환불과의 연결

prepare가 반환한 orderId/amount로 기존 `POST /api/user/payments/confirm`을 호출한다. 승인 시작은 원래 5분 안에 영속 저장하고 외부 PG 호출은 바깥 트랜잭션과 행 잠금 없이 진행한다. 같은 요청의 검증된 DONE만 원래 테이블·시간을 한 번 확정한다. 유효한 시작 기록이 있으면 최초 만료 뒤의 같은 DONE도 가능하다. READY/TIMEOUT/FAILED만으로 예약을 확정하지 않고 종료 예약을 되살리지 않는다. 알림은 기존 커밋 후 계약을 유지한다.

이 통합 후보는 기존 [PR #192](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/192)의 승인·재확인·환불 보호를 정상 merge했다. 최초 유효 환불 요청과 한도 저장·환불 중 변경 동결이 승인된 [#188](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/188) 이후의 계약을 따른다. 준비 API가 돈만 환불하는 경로와 예약 취소를 합치지는 않는다. [기능60](feature-60-refund-execution.md)·[기능61](feature-61-refund-preview.md)와 각각의 소스 상태를 확인한다. 아래의 이전 4B 보류 기록은 당시 상태다.

### 검증과 남은 연결

전용 PostgreSQL 16, 기본 max_connections=100, Java 21에서 새 prepare54개와 관련 회귀424개가 통과했다. 새 빈 DB의 최종 `./gradlew clean build --no-daemon`은 **66클래스 708개, 실패/오류/건너뜀0**, bootJar 생성 성공이다. 실행별 중복 수나 과거 #192의 628개를 더한 값이 아니다. [작업 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/f81bcaa95de0244d76ff4a2d40e9d89165d06c55/work-log/195-payment-prepare-snapshot.md), [구현](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/f81bcaa95de0244d76ff4a2d40e9d89165d06c55/src/main/java/com/catchtable/payment/service/PaymentPrepareService.java), [DTO](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/f81bcaa95de0244d76ff4a2d40e9d89165d06c55/src/main/java/com/catchtable/payment/dto/PaymentPrepareResponse.java)를 기준으로 한다. 소스 게시와 정확한 head의 CI 결과는 별도 증거다.

금액 위변조 무효, READY 원본 재사용, 동시 prepare1건, 정확한 만료 경계, 승인/만료 경합, 준비→기존 confirm DONE→환불/취소를 검증했다. 마지막 흐름은 **신뢰된 저장 유료 fixture부터의 fake 검증**이며 실제 유료 선점 생성부터의 E2E가 아니다. 실제 Toss·프런트 화면/코드·JWT·배포는 검증하지 않았다. 이 문서의 링크·계약·diff 검사와 문서 PR CI는 소스 PR 검증을 대신하지 않는다.

기능33 설정·실제 유료 생성 연결과 공유 V20 적용 이력·rollout은 남아 있다. 25개 기존 마이그레이션을 그대로 유지했다. V20 없는 populated V22 DB는 예상대로 Flyway 업그레이드가 차단되며 빈 DB 성공으로 이를 해결했다고 표시하지 않는다. repair/rename/outOfOrder/ignore를 적용하지 않는다. 기능 소스의 Draft 게시·정확한 head CI·머지와 문서 PR은 별개이며 #195/#65를 이 문서만으로 닫지 않는다.

---

## 이전 계약·검증 기록

다음은 기준 dev 문서의 기록을 그대로 보존한 것이다. 아래 “현재/미구현/결정 대기” 및 과거 테스트 수는 각 기록 당시의 상태이며, 준비의 현행 후보 계약은 위 절을 따른다.


- 확인일: 2026-10-27
- 문서 작업 이슈: [#122](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/122)
- 명세: [기능10](https://app.notion.com/3d548d76fa32819386a3e858c26b2c1b)
- 관련: [기존 기능 이슈 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/controller/ReservationController.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/pages/reservation/ReservationPage.tsx)

## 목적과 현재 범위

프런트의 예약금 화면은 목업 결제 흐름이며, 백엔드에는 결제 준비·승인 API와 공통 결제 모델이 없다. 이번 문서는 구현 예정 계약과 현재 호출 가능한 무료 예약·선점을 구분한다.

## 연동 계약과 확인 사항

- 예약금은 인원과 무관한 예약 건당 고정 금액이다. 프런트의 depositPerPerson × partySize 계산을 서버가 준비한 금액 기준으로 맞춰야 한다.
- 무료 예약은 POST /api/user/reservations로 즉시 확정한다. 유효한 선점에서 결제를 시작하고 서버가 성공을 재확인한 경우의 유료 확정은 공통 결제 계약 후 연결한다.
- POST /api/user/payments/prepare 및 /confirm은 예정 API다. 현재 호출 가능한 구현으로 표시하지 않는다.
- 프런트의 임시 paymentKey와 테스트용 결제창은 실제 Toss 승인 결과가 아니다. 서버가 검증한 주문·금액·성공 결과를 사용해야 한다.
- 금액과 환불 정책 종류는 최초 예약 스냅샷을 사용한다. 인원 변경을 이유로 예약금을 다시 곱하거나 차액 결제를 만들지 않는다.

## 남은 연결

예약금 설정은 기능33 담당, 결제 상태·멱등성·결과 재확인은 윤서 담당 기능58·62·63과 맞춘 뒤 연결한다. 실제 승인과 취소 실행, 공통 모델·상태명은 이 문서에서 새로 정하지 않는다.

## 2026-10-29 재확인 후속 검토 메모

기준 dev `7da9c1db77dac1327b565e8d1f36bd3961ea57c6`에는 아래 재확인·예약 정산 후속이 아직 없다. 공통 [PR #179](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/179)의 제안과 별도 [기능10 수정 #180](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/180)의 로컬 후보를 함께 검토할 때 사용하는 메모다. 기존 [PR #145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145)도 Draft·미머지이며 이 문서 반영이 API 공개·연동 완료를 뜻하지 않는다.

재확인에서도 최초 승인 연결의 회원·주문·키·금액과 최초 예약 스냅샷을 검사한다. 불일치는 `409 PAYMENT_MISMATCH`이며 진행 중인 자리와 연결을 유지한다. 이미 확정한 뒤 취소·완료·노쇼가 된 예약은 재확인으로 변경하지 않는다. 확정된 적 없는 CANCELLED/EXPIRED의 늦은 승인은 예약을 되살리지 않고 공통 후보의 `refundRequired` 대상으로 남긴다. 같은 원래 키 DONE의 늦은 확정과 커밋 후 알림1회 계약은 유지한다.

confirm은 기존 `PaymentResponse` 형식이고, 공통 후보의 sync·상세는 같은 최상위 결제 필드에 `refunds` 배열이 추가된다. `refundRequired`는 실제 환불 실행 결과가 아니다. 기능33 설정/prepare, 사용자4B의 양수 환불 실행, 공유 V20 적용 전략, 실제 Toss·프런트/JWT·배포는 별도 범위다. 이 문서 후보는 링크·기준 소스·Markdown·diff만 검사하며 앱 테스트 증거는 별도 소스 후보와 구분한다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.

## 2026-10-29 후속 — 공통 승인 서비스와 남은 연결

앞선 날짜의 기록은 보존한다. 이번 기준은 dev `555e0aabd243b52e5007e55460d2aae398952f27`이다. 공통 기능58 [PR #144](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/144)·기능62 [PR #153](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/153)은 반영되어 [결제 모델](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/555e0aabd243b52e5007e55460d2aae398952f27/src/main/java/com/catchtable/payment/entity/Payment.java)·[승인 서비스](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/555e0aabd243b52e5007e55460d2aae398952f27/src/main/java/com/catchtable/payment/service/PaymentApprovalService.java)가 있다. 과거의 “공통 결제 모델이 없다”는 현재 dev 설명으로 사용하지 않는다.

[현재 결제 컨트롤러](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/555e0aabd243b52e5007e55460d2aae398952f27/src/main/java/com/catchtable/payment/controller/UserPaymentController.java)는 목록·상세만 제공한다. `POST /api/user/payments/prepare`·`/confirm`과 승인→예약 확정 연결은 이 dev 및 공개 기능10 [Draft PR #145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145)의 완료 기능이 아니다. [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65)는 스냅샷 저장 경계만으로 닫지 않는다.

- 준비 금액은 기능33 담당의 예약금 사용 여부·건당 고정 금액·환불 정책 계약이 먼저 필요하다. 매장 필드나 인원당 기본 금액을 임의로 만들지 않는다.
- `PaymentApprovalService.approve(memberId, orderId, paymentKey, amount)`는 바깥 트랜잭션 없이 호출한다. Toss 4xx는 `200`의 `FAILED` 결제를 반환할 수 있다. `READY`도 확정 성공이 아니며, 서버가 검증한 `DONE`일 때만 확정 대상으로 판단한다.
- 승인 시작 전 `READY`는 최초5분을 유지하고 준비·조회로 연장하지 않는다. 승인 진행·결과 불명에는 자리를 보존해야 한다. 현재 dev의 만료 작업·가용 조회·명시적 해제는 이 경계와 미연결이다. 프런트가 새로고침이나 남은 시간0만으로 외부 승인 실패·자리 반환을 확정하면 안 된다.
- 유효기간 안 승인 시작 후 최초 만료시각을 넘겨 도착한 `DONE`을 바로 확정할지는 사용자 결정 대기다. 공통 서비스가 있다는 이유로 임의 확정하지 않는다.
- 스냅샷 V20은 미머지 Draft에 있고 dev는 V21·V22까지 진행되었다. 영속 DB의 V20 이력 및 안전한 적용 순서 확인 전 PR #145를 적용 가능한 상태로 표시하지 않는다.
- [기능63 #142](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/142)의 재확인·웹훅·자동 보정은 별도 담당 범위다. 이번 문서는 예약금 설정·실행 endpoint·프런트 코드·실제 Toss·배포를 구현하지 않는다.

검증은 위 커밋의 코드·기존 계약·링크·Markdown·`git diff --check` 대조다. 문서 변경으로 앱 테스트나 화면 연동을 새로 확인한 것으로 보고하지 않는다.

## 2026-10-29 실제 결정 반영 — 유효 선점에서 시작한 승인 확정

앞선 “결정 대기·미구현”은 당시 기록으로 보존한다. 이 절은 dev `7d320491a6ed9107ac3149ed8cfaa1bb1dad45bb`와 기능10 [후속 소스 c3f9710](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939)의 검토 계약이며, 기능 [Draft PR #145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145)는 배포·upgrade 완료가 아니다.

- 구현 경로는 `POST /api/user/payments/confirm`이다. `X-Member-Id`와 JSON `{"orderId":"준비된 주문","paymentKey":"동일 결제 키","amount":10000}`를 보낸다. 반환은 기존 `PaymentResponse`이며 클라이언트가 회원·정책·actor를 본문으로 정하지 않는다. [컨트롤러](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/payment/controller/UserPaymentApprovalController.java), [요청 DTO](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/payment/dto/PaymentConfirmRequest.java)
- 기능33의 예약금 설정 원본·점주 계약이 없어 `/prepare`와 유료 생성 연결은 제공하지 않는다. 이미 저장된 올바른 주문·양수 금액·최초 정책 스냅샷만 승인 대상이다. 건당 금액을 인원수로 곱하거나 현재 매장 설정으로 보충하지 않는다. 테스트의 준비 원장 fixture를 유료 생성 전체 E2E로 보지 않는다.
- 사용자5A: 최초5분 안에 시작 시각·paymentId·orderId·paymentKey를 커밋한 뒤 외부 승인을 호출한다. 이후 서버 원장이 같은 키 `DONE`이면 원래 만료시각 뒤에도 **같은 테이블·같은 시간**을 한 번 확정한다. 확정 시각과 `RESERVATION_CONFIRMED` 알림도 한 번이며 알림은 커밋 후 전달한다. [승인 연결 서비스](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/reservation/service/ReservationPaymentService.java)
- `200 DONE`만 확정 성공이다. 공통 계약의 확정 거절은 `200 FAILED`, 결제 유효시간 만료 확인은 `200 CANCELED`이며 예약을 확정하지 않고 기존 해제 경로로 즉시 반환한다. `READY`·`TIMEOUT`·진행/결과 불명은 `409 PAYMENT_RECONCILIATION_REQUIRED`로 자리를 보존한다. 입력400·회원 식별401·남의 주문404·주문/금액/키 불일치409도 성공으로 처리하지 않는다.
- 같은 최초 요청만 재시도하며 키·주문·금액을 바꾸지 않는다. 최초 시작부터15일 이상 남은 READY/TIMEOUT은 외부 재승인 없이409로 보존한다. 이미 같은 키 DONE이 저장됐으면 외부 재호출 없이 확정할 수 있다. [Toss 멱등키 유효기간](https://docs.tosspayments.com/reference/using-api/authorization)과 공통 기능142의 재확인 경계를 따른다.
- 승인 전 READY는 원래5분이며 준비·조회로 연장하지 않는다. 진행 중 `remainingSeconds=0`이어도 PENDING_PAYMENT는 실패·해제를 뜻하지 않는다. 과거 raw pending의 불명/승인 원장은 시각·키를 추정하지 않는 격리 표식으로 보존한다. 만료·수량·설정 충돌·명시적 해제 모두 같은 자리 보호를 적용한다. 세부 화면 처리는 [기능54](feature-54-hold-countdown.md)·[기능55](feature-55-hold-release.md)·[기능56](feature-56-hold-expiry-rebooking.md)을 따른다.
- 실제 답변2A/3A/4B에 따라 돈만 환불하는 경로와 예약 취소를 구분하며 양수 취소 환불의 client Idempotency-Key는 미래 계약이다. 최초 요청시각/한도 저장·환불 중 변경 동결이 합의되기 전 양수 환불 endpoint·PG 기반 예약 취소는 보류한다. [기능60](feature-60-refund-execution.md)·[기능61](feature-61-refund-preview.md)의 기존 차단을 유지한다.

V20은 공유 적용 이력이 미확인이므로 원본을 보존했고 V23은 적용 전략 자리로 남겼다. V24의 새 승인 보호를 빈 DB에서 검증해도 V20 없는 populated V22 DB의 upgrade 차단은 해결되지 않는다. 복구·웹훅·예약금 설정·실제 Toss·프런트/JWT·배포는 별도 범위다. 이 문서 변경 자체는 소스·링크·Markdown·diff만 검증하며 앱 테스트/실제 CI 완료 증거를 대신하지 않는다.
