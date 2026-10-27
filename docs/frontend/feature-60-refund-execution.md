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
