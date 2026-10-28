# 기능60 — 전액·부분 환불 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#130](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/130)
- 명세: [기능60](https://app.notion.com/3d548d76fa3281369431cf4bd1c07a00)
- 관련: [기존 기능 이슈 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/service/ReservationService.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/payment/api/paymentApi.ts)

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
