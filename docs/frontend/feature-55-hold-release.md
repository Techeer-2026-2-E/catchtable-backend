# 기능55 — 결제 실패·이탈 시 테이블 반환 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#128](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/128)
- 명세: [기능55](https://app.notion.com/3d548d76fa3281d4bc29d5f06ca81c68)
- 관련: [기존 기능 이슈 #63](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/63)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/service/ReservationService.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/pages/reservation/ReservationPage.tsx)

## 목적과 현재 범위

현재 명시적 해제 API와 향후 결제 결과에 따른 반환 경계를 기록한다. 승인 중·결과 불명 처리는 계약 전 임의로 정하지 않는다.

## 연동 계약과 확인 사항

- DELETE /api/user/reservations/holds/{holdId}에 X-Member-Id를 보낸다. 성공은 204다.
- 본인 선점만 해제할 수 있다. 같은 해제를 반복해도 중복 종료하지 않고, 이미 만료된 선점은 EXPIRED를 유지한다.
- 확정 예약을 이 API로 취소하려 하면 409다. 예약 취소 API와 선점 해제를 혼용하지 않는다.
- 명시적 결제 취소와 단순 화면 이탈을 구분한다. 단순 이탈은 5분 만료가 기준이므로 화면을 떠났다는 사실만으로 결제 실패를 단정하지 않는다.
- 확인된 외부 결제 실패, 승인 진행 중 요청, 응답 유실·결과 불명에 따른 반환은 아직 연결되지 않았다. 결과를 확인하기 전에 실패로 추정해 테이블을 반환하지 않는다.

## 남은 연결

#63과 공통 기능58·62·63에서 실패 확인·반환 허용 시점·재확인을 맞춰야 한다. 프런트의 화면 이탈 시 자동 해제를 이 정책에 맞춰 확인한다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.

## 2026-10-29 승인 결과와 명시적 해제 후속

`DELETE /api/user/reservations/holds/{holdId}`는 기존 `X-Member-Id`와 성공204를 유지하지만 승인 진행/결과 불명/과거 격리 중에는 `409 RESERVATION_NOT_CANCELLABLE`로 자리를 보존한다. [해제 검사](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/reservation/entity/Reservation.java)와 [승인 완료 처리](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/c3f971013ae5cfbb7072bb3b3d8691ebe5d3b939/src/main/java/com/catchtable/reservation/service/ReservationPaymentService.java)가 같은 보호를 따른다. 서버에서 확인한 FAILED/CANCELED만 보호를 해제한 뒤 기존 release 경로로 즉시 반환하며 기한 뒤에는 EXPIRED가 된다. READY/TIMEOUT이나 화면 이탈을 실패로 바꾸지 않는다. 세부 결과는 [기능10](feature-10-deposit-payment.md)을 따른다. Draft 소스 계약 검토이며 실제 PG·프런트·배포 확인은 아니다.
