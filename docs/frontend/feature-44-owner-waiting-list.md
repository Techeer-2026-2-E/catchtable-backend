# 기능44 — 점주 대기 고객 목록 프런트 연동

- 확인일: 2026-10-27
- 문서 작업 이슈: [#123](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/123)
- 명세: [기능44](https://app.notion.com/3d548d76fa328152bfb5fd066d2d85e4)
- 관련: [기존 목록 구현 PR #94](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/94)
- 구현 기준: [백엔드 dev@61aaf74](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/61aaf744eab5fbfb8024e1c2fe83b915124c4f41)
- 대조한 코드: [백엔드](../../src/main/java/com/catchtable/waiting/dto/OwnerWaitingResponse.java), [프런트 초안 e47b63b](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/owner-waiting/hooks/useOwnerWaitings.ts)

## 목적과 현재 범위

현재 백엔드의 점주 대기 목록·SSE를 기준으로 프런트 초안의 필드와 10초 폴링 연결을 정리한다. 기존 백엔드 목록을 다시 구현하는 작업이 아니다.

## 연동 계약과 확인 사항

- GET /api/owner/stores/{storeId}/waitings는 X-Member-Id를 받고 점주 유형과 매장 소유권을 검사한다. 한국 시간 당일의 활성 대기 목록을 반환한다.
- 목록의 id, waitingNumber, partySize를 프런트 초안의 waitingId, waitNumber, partyCount와 맞춰야 한다. 응답에 없는 memberId·customerPhone을 제공된 값으로 가정하지 않는다.
- 점주 구독은 GET /api/owner/stores/{storeId}/waitings/subscribe?memberId={ownerId}다. connect와 WAITING_CHANGED 수신 시 해당 매장 목록을 다시 조회한다.
- 고객 전용 SSE 구독만으로 점주 목록을 갱신할 수는 없다. 재연결 시에도 재조회하며 누락 이벤트 재생을 전제로 하지 않는다.
- compatibleTables는 설정과 인원이 맞는 테이블이며 실제 공석을 보장하는 필드가 아니다.

## 남은 연결

프런트의 필드 매핑과 점주 구독 연결, 다른 담당자의 상태 변경 이벤트 연결은 각 실제 구현에서 확인한다. 기능16 순번 알림 이슈 #84와 기능44 목록은 별도 범위다.

## 문서 검증

컨트롤러·DTO·서비스와 명시한 프런트 소스를 대조했고 상대 링크와 git diff --check를 확인했다. 문서만 변경하므로 앱 테스트를 새로 실행한 것으로 보고하지 않는다. 프런트 저장소 수정·화면 실행·Toss 실행·배포 검증은 포함하지 않는다.
