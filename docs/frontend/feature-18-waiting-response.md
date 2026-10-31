# 기능18 — 호출 수락과 방문 의사 표시

2026-10-31. [노션 명세](https://app.notion.com/p/3d548d76fa3281358b3aeee972d44d59), [변경·검증 기록](../../work-log/2026-10-31-waiting-response-display.md).

## 요청과 표시 기준

`POST /api/user/waitings/{waitingId}/accept`는 호출된 내 웨이팅의 방문 의사를 기록한다. 응답의 `status`는 `CALLED`, `arrivalIntent`는 `COMING`이며, `respondedAt`과 기존 `expiresAt`을 반환한다. 성공을 `CONFIRMED` 전환이나 착석으로 해석하지 않는다.

`GET /api/user/waitings/{waitingId}`에도 `respondedAt: string | null`, `arrivalIntent: "COMING" | null`을 추가했다. 진행 중인 웨이팅에서 두 값으로 수락 완료를 복구한다. 수락 전에는 `null`이고, 종료되면 기존 호출 시각·제한시간과 함께 `null`이다. 내 웨이팅만 조회하는 회원·소유권 검사는 유지한다.

점주 목록의 기존 `respondedAt`·`arrivalIntent`로 방문 예정과 응답 시각을 표시한다. 고객 수락 성공 후 상세를 재조회하며, 점주 `WAITING_CHANGED` 수신 후 목록을 재조회한다. 새 상태나 SSE 이벤트를 만들지 않는다.

## 재요청과 이후 흐름

- 반복 수락은 최초 응답 시각·기존 제한시간을 유지하며 추가 알림을 발행하지 않는다.
- 점주 재호출은 응답 정보를 초기화하므로 고객이 새 호출에 다시 응답할 수 있다.
- 고객 응답과 점주의 착석 처리는 별개이며, 점주 입장 처리 뒤 상태는 `SEATED`다.
- 기존 `CONFIRMED` 데이터의 표시 호환은 보존하고 새 수락의 전환 대상으로 사용하지 않는다.

## 확인 범위

전용 PostgreSQL 16 DB 전체 빌드 786개와 MSW를 끈 실제 고객·점주 화면에서 수락·새로고침·반복·재호출·착석을 확인했다. 목 API도 별도 컨텍스트에서 검증했다. CI·배포·Notion 원문 갱신은 포함하지 않는다.
