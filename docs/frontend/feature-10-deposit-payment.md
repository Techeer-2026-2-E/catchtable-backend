# 기능10 — 예약금 가결제 프런트 연동

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

## 2026-10-29 후속 — 기능63 결과 재확인

앞선 기록은 보존한다. 앞선 "최초 만료시각 뒤 DONE 확정" 결정 대기는 work-log 65의 결정 5A대로 같은 최초 승인 시도의 보호가 있으면 확정한다. [기능63 결과 재확인](feature-58-payment-status.md#결과-재확인-기능63)이 `sync`·웹훅·자동 재확인으로 `TIMEOUT`·승인 진행 건을 Toss 조회 결과로 확정·반환하고, 보호 없는 늦은 승인은 확정 없이 `refundRequired`로 표시한다. 예약금 설정·prepare·실제 Toss·프런트는 여전히 범위 밖이다.


## 2026-10-29 후속 — 재확인 시 기존 예약 경계 보존

기능10 후속 후보는 `settle`에서도 최초 승인 연결과 회원·주문·키·금액·예약 스냅샷을 검사한다. 불일치는 409 PAYMENT_MISMATCH이며 진행 중 자리와 연결을 유지한다. 이미 확정한 뒤 취소·완료·노쇼가 된 예약은 재확인으로 변경하지 않는다. 확정된 적 없는 CANCELLED/EXPIRED의 늦은 승인은 예약을 되살리지 않고 기존 `refundRequired` 대상으로 남긴다. 실제 환불 실행은 제공하지 않는다. [수정과 회귀 검증](../../work-log/65-deposit-snapshot.md#2026-10-29-후속--기능63-정산-경로의-예약-경계)을 참조한다.
