# 기능53 — 결제 중 테이블 임시 선점 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#126](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/126)
- 명세: [기능53](https://app.notion.com/3d548d76fa3281f7834ee32be4c17f60)
- 관련: [기존 기능 이슈 #60](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/60)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/dto/ReservationHoldResponse.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/entities/reservation/model.ts)

## 목적과 현재 범위

기존 선점 생성 API와 프런트 상태 타입을 대조한다. 선점 코어의 구현과 외부 결제 확정을 구분한다.

## 연동 계약과 확인 사항

- POST /api/user/reservations/holds에 X-Member-Id와 storeId, reservationStartAt, partySize를 보낸다. 실제 테이블은 서버가 자동 배정한다.
- 선점은 PENDING_PAYMENT 예약 자체이며 holdId와 reservationId가 같다. 생성 시점부터 서버 기준 5분인 expiresAt을 사용한다.
- 응답의 PENDING_PAYMENT·CONFIRMED·CANCELLED·EXPIRED를 프런트의 HOLDING·PAID·RELEASED 가정과 맞춰야 한다. 일반 예약 상태 표시에도 결제 대기·만료를 반영한다.
- 서버 응답에는 현재 예약금 금액이 없다. 프런트가 선점 응답에 depositAmount가 있다고 가정하거나 인원수로 금액을 산정하지 않는다.
- 무료 예약 생성과 선점 생성은 현재 별도 API다. 예약금 설정에 따른 유료·무료 흐름 선택과 결제 시도 연결은 아직 구현되지 않았다.
- 가용 여부 조회 뒤에도 배정 경쟁으로 409가 발생할 수 있다. 충돌 시 최신 예약 가능 시간을 다시 조회한다.

## 남은 연결

#60은 선점 생성·재고 반영 범위를 완료해 닫혀 있다. 결제 연결은 #65 및 윤서 담당 공통 결제 계약에서 진행하며 완료된 #60을 다시 여는 작업이 아니다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.

## 2026-10-29 승인 보호 연동 후속

기본 선점은 최초5분이고 조회로 늘어나지 않는다. 유효한 기한 안에 시작을 저장한 승인 진행/결과 불명은 원래 자리와 PENDING_PAYMENT를 보존한다. [승인 보호 소스](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/reservation/entity/Reservation.java)의 검토 계약이며 준비 API·기능33 유료 설정은 미연결이다. [기능10](feature-10-deposit-payment.md)과 [기능54](feature-54-hold-countdown.md)를 따른다. 소스 Draft·미머지 및 V20 upgrade 차단을 문서 게시로 해소하지 않는다. 기존 #60 완료 이슈를 다시 여는 작업이 아니며 프런트 코드는 변경하지 않았다.
