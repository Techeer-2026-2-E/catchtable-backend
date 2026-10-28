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
