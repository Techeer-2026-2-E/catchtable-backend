# 기능47 순번 연기 프런트 연동 안내

확인일: 2026-11-05. [Notion 기능47](https://app.notion.com/p/3d548d76fa3281009a46cbba47d4c18f)가 요구사항 정본이다. 백엔드 [PR #239](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/239)는 `dev`에 머지됐고, 구현 이슈 #229는 완료 종료했다.

백엔드 대조 기준은 `dev@484daf3aa88913f382baf6468ace398653e16a0d`, 프런트는 `dev@015f333aa2b2d4230560d69013136190b5ea2c87`이다. 소스 대조 결과이며 실제 화면을 실행한 결과는 아니다.

## 현재 프런트에서 남은 연결

- 점주 API·mutation·화면에 `/defer` 요청 연결이 없다.
- 점주 목록 화면은 서버 목록을 받은 뒤 `waitingNumber` 오름차순으로 다시 정렬한다. 서버의 실제 순서가 연기로 바뀌어도 화면에서 접수번호 순서로 되돌아갈 수 있다.
- 점주 SSE의 `WAITING_CHANGED`·재연결 시 재조회, 고객 순번 변경 SSE 처리는 이미 있다.

## API와 결과

`POST /api/owner/waitings/{waitingId}/defer`, 요청 본문 없음. 기존 `X-Member-Id`로 점주를 식별한다.

| 결과 | 조건·내용 |
| --- | --- |
| 200 | `WAITING`인 팀을 바로 뒤 진행 중 팀과 맞바꿈. 한 웨이팅당 최대2회 |
| 409 `WAITING_NOT_DEFERRABLE` | 대기 중이 아니거나 뒤 팀이 없음 |
| 409 `WAITING_DEFER_LIMIT_EXCEEDED` | 3번째 연기 |
| 401 | 회원 식별 헤더 누락·형식 오류 |
| 403 | 점주가 아닌 회원 |
| 404 | 존재하지 않거나 다른 점주의 웨이팅 |

성공 응답 예시:

```json
{
  "waitingId": 101,
  "waitNumber": 7,
  "deferCount": 1,
  "beforePosition": 1,
  "afterPosition": 2
}
```

`waitNumber`는 처음 발급된 고정 접수번호다. 현재 순번은 `beforePosition`·`afterPosition`이며 두 의미를 섞지 않는다. 백엔드는 별도 `queue_order`를 바꾸고 변경 전후 순번·처리자·시각을 이력에 저장한다.

## 목록 순서와 SSE

점주 목록 `GET /api/owner/stores/{storeId}/waitings`는 서버가 `queue_order` 기준으로 정렬해 반환한다. 화면은 이 배열의 순서를 유지하고 고정 `waitingNumber`로 재정렬하지 않는다. 접수번호 자체의 표시는 그대로 유지한다.

연기 성공 뒤 목록을 재조회한다. 다른 접속의 점주는 기존 `WAITING_CHANGED`로, 순번 알림을 허용한 고객은 기존 순번 변경 이벤트로 최신 데이터를 조회한다. 이벤트 재생을 가정하지 않고 연결·재연결 후 재조회도 유지한다. 선택 알림이 꺼져 있을 때도 화면 진입·새로고침의 조회는 유지한다.

현재 점주 목록 DTO에는 `deferCount`·`queue_order` 필드가 없다. `deferCount`는 연기 성공 응답으로만 받으므로 새로고침 뒤에도 연기 횟수를 상시 표시하려면 별도 API 계약 검토가 필요하다. 이번 문서에서 필드를 추가하거나 표시 정책을 새로 정하지 않았다.

## 프런트 담당에게 넘길 작업

- 연기 API 함수·mutation·버튼과409 안내를 연결한다.
- `OwnerWaitingsPage.tsx`의 `waitingNumber` 재정렬을 제거하고 서버 순서가 유지되는지 확인한다.
- 연기·다른 팀 호출/취소·SSE 재연결 뒤 점주 목록과 고객 순번이 일치하는지 실제 환경에서 확인한다.

근거: [점주 목록 정렬·연기 서비스](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/484daf3aa88913f382baf6468ace398653e16a0d/src/main/java/com/catchtable/waiting/service/OwnerWaitingService.java), [연기 응답](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/484daf3aa88913f382baf6468ace398653e16a0d/src/main/java/com/catchtable/waiting/dto/WaitingDeferResponse.java), [프런트 점주 목록 화면](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/pages/owner/waitings/OwnerWaitingsPage.tsx#L68), [프런트 점주 API](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/owner-waiting/api/ownerWaitingApi.ts), [점주 SSE](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/owner-waiting/hooks/useOwnerWaitings.ts).

이번 변경은 소스 대조와 문서 작성이다. 테스트를 새로 실행하거나 프런트 코드·백엔드 정렬·연기 정책을 변경하지 않았다. 실제 화면·공유 DB 적용·배포는 확인하지 않았다.

## 웨이팅 SSE 전달 보완 — 2026-11-11

웨이팅 업무와 알림 작업을 함께 저장하고 전용 worker가 비동기로 전달하므로 HTTP 응답보다 알림이 늦을 수 있다. 이벤트명·payload·수신 설정 정책과 재연결 후 재조회 계약은 유지한다. 용량 초과 시 관련 업무는 rollback되고 `503 WAITING_SSE_CAPACITY_EXCEEDED`와 `Retry-After: 30`을 반환한다. 재시도 전에 현재 상태를 조회하며, 고객 수신 보장이나 과거 이벤트 재생으로 해석하지 않는다. [동작·운영 계약](../waiting-sse-durability.md)을 참고한다.
