# 기능56 — 선점 만료 후 재예약 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#129](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/129)
- 명세: [기능56](https://app.notion.com/3d548d76fa32813a94fbdec00b9f54e9)
- 관련: [기존 기능 이슈 #64](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/64)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/reservation/repository/ReservationRepository.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/pages/reservation/ReservationPage.tsx)

## 목적과 현재 범위

선점 기한이 끝난 뒤 재조회·재예약하는 현재 흐름과 외부 승인 경합의 미연결 범위를 구분한다.

## 연동 계약과 확인 사항

- 서버 현재 시각이 holdExpiresAt과 같거나 이후이면 선점은 만료다. 응답 상태는 EXPIRED이며 남은 시간은 0이다.
- 스케줄 정리 전에도 조회는 만료를 반영하고, 신규 예약·선점 배정 경로는 만료된 재고를 정리해 다시 사용할 수 있게 한다.
- 프런트는 만료된 holdId로 결제를 이어가지 않고 예약 가능 시간을 재조회한 뒤 새 선점을 생성해야 한다.
- 반환된 같은 테이블·시간 구간의 새 요청도 행 잠금과 시간 중복 제약을 거친다. 동시에 들어온 요청 모두의 성공을 보장하지 않는다.
- 만료 후 지연된 결제 성공만으로 예약을 확정하지 않는 정책이다. 실제 외부 승인·만료 경합과 결과 재확인·취소/환불 보정은 공통 결제 계약 후 연결한다.

## 남은 연결

#64의 승인 경합·지연 결과 보정은 미구현이다. 백엔드의 현재 만료·재예약 테스트와 외부 결제 경합 검증을 구분한다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.
