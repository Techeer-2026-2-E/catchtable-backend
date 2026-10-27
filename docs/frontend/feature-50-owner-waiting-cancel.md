# 기능50 — 점주 웨이팅 취소 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#125](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/125)
- 명세: [기능50](https://app.notion.com/3d548d76fa3281a98a5ac95e9dde8a3d)
- 관련: [기존 기능 이슈 #93](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/93)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/waiting/dto/OwnerWaitingCancelRequest.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/owner-waiting/api/ownerWaitingApi.ts)

## 목적과 현재 범위

점주 취소의 필수 사유 본문과 204 응답, 고객·대기열 갱신을 프런트 담당자가 연결할 수 있게 기록한다.

## 연동 계약과 확인 사항

- DELETE /api/owner/waitings/{waitingId}에 X-Member-Id와 {"reason":"매장 사정"} JSON 본문을 보낸다. 사유는 공백이 아닌 300자 이하다.
- 성공은 204이며 응답 본문이 없다. 삭제 호출에 사유 본문을 넣지 않는 현재 프런트 초안을 맞춰야 한다.
- 점주 유형과 매장 소유권을 검사한다. 최초 취소 사유를 보존하고 같은 취소의 재요청으로 종료를 반복하지 않는다.
- 실제 종료가 커밋되면 고객에게 WAITING_CANCELED와 남은 고객의 WAITING_POSITION_CHANGED가 전달된다. 점주 목록은 WAITING_CHANGED 또는 재조회로 갱신한다.
- 순번 payload는 waitingId, currentPosition, teamsAhead, status다. 롤백·종료 재시도를 새 순번 변경으로 처리하지 않는다.

## 남은 연결

프런트의 사유 입력·DELETE 본문 전송과 목록 재조회가 대상이다. 고객 취소 API나 대기열 상태를 이 문서에서 변경하지 않는다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.
