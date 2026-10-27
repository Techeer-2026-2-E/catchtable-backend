# 기능49 — 웨이팅 미응답·노쇼 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#124](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/124)
- 명세: [기능49](https://app.notion.com/3d548d76fa3281d78299dca9955ab4f7)
- 관련: [기존 기능 이슈 #92](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/92)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/waiting/dto/OwnerWaitingNoShowRequest.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/owner-waiting/api/ownerWaitingApi.ts)

## 목적과 현재 범위

백엔드에 구현된 점주 수동 미응답·노쇼 처리와 프런트 초안의 요청 본문·상태·알림 차이를 정리한다. 자동 만료 자체는 별도 기능이다.

## 연동 계약과 확인 사항

- POST /api/owner/waitings/{waitingId}/no-show에 X-Member-Id와 JSON 본문을 보낸다.
- 본문 예시는 {"reason":"NO_RESPONSE","detail":"호출 후 응답 없음"}이다. reason은 NO_RESPONSE 또는 NO_SHOW, detail은 공백이 아닌 300자 이하다.
- 성공 응답은 200과 종료 결과(waitingId, status, reason, detail, endedAt)다. OwnerWaiting 목록 항목 전체를 반환하는 응답으로 취급하지 않는다.
- 조회되는 종료 상태는 NO_SHOW다. 기존 고객 이벤트명 WAITING_EXPIRED는 유지하므로 이벤트 이름만 보고 상태를 EXPIRED로 저장하지 않는다.
- 같은 종료 재시도와 종료 후 사유 정정을 구분한다. 사유만 정정한 경우 고객 알림·순번 변경을 중복 처리하지 않는다.
- 실제 종료가 커밋되면 남은 고객에게 WAITING_POSITION_CHANGED가 전달된다. 수신 후 payload 또는 순번 조회로 최신 상태를 반영한다.

## 남은 연결

프런트의 사유·설명 입력과 필수 본문 전송을 연결한다. 자동 만료는 #109에서 다루며 이 문서로 구현 완료 처리하지 않는다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.
