# 기능54 — 테이블 선점 만료시간 표시 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#127](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/127)
- 명세: [기능54](https://app.notion.com/3d548d76fa3281a199abea9d67d1e616)
- 관련: [기존 기능 이슈 #62](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/62)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/dto/ReservationHoldResponse.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/pages/reservation/ReservationPage.tsx)

## 목적과 현재 범위

서버에서 이미 제공하는 기한·시각과 프런트 카운트다운을 연결할 기준을 기록한다. 화면 타이머 구현과 결제 접수 검증은 별도다.

## 연동 계약과 확인 사항

- GET /api/user/reservations/holds/{holdId}에 X-Member-Id를 보낸다. 본인 선점만 조회할 수 있다.
- expiresAt은 최초 생성 시각부터 5분이며 재조회·새로고침으로 연장되지 않는다. serverTime과 remainingSeconds도 제공한다.
- 프런트는 로컬 시계만으로 계산하지 말고 서버 시각 차이를 보정해야 한다. 남은 시간은 0 미만으로 표시하지 않는다.
- 0초이면 결제 진행을 차단하고 예약 가능 시간을 다시 조회하도록 안내한다. 상태가 PENDING_PAYMENT가 아니면 남은 시간은 0이다.
- 클라이언트 타이머는 안내용이다. 실제 결제 접수·승인 시 서버의 선점 유효성 검사는 공통 결제 연결 때 함께 보장해야 한다.

## 남은 연결

#62는 화면 연동·만료 후 결제 차단이 남아 있다. 카운트다운 문서를 게시한 것을 화면이나 결제 API 완료로 처리하지 않는다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.

## 2026-10-29 승인 진행 중 0초 표시 후속

승인 시작 전에는 원래5분이 신규 승인 자격의 경계다. 기한과 같거나 이후인 새 요청은 거부하며 조회/준비로 기한을 늘리지 않는다. 이미 유효하게 시작한 승인·결과 불명은 `remainingSeconds=0`과 PENDING_PAYMENT가 함께 올 수 있으므로 실패·만료·자리 반환으로 단정하거나 새 키로 결제하지 않는다. [응답 DTO](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/reservation/dto/ReservationHoldResponse.java)와 [기능10](feature-10-deposit-payment.md)의 DONE/FAILED/409 구분을 따르고 상태를 다시 조회한다. 과거 격리 행도 확인 전 보존한다. 이 절은 이전 0초 안내를 보완한 소스 후보 계약이며 화면 구현·배포 완료가 아니다.
