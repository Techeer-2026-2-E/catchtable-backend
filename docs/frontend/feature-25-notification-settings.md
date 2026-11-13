# 기능25 알림 수신 설정 프런트 연동 안내

확인일: 2026-11-05. [Notion 기능25](https://app.notion.com/p/3d548d76fa328174ac06d1133438dbbb)가 요구사항 정본이다. 백엔드는 [PR #240](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/240) 머지 후 `dev@484daf3aa88913f382baf6468ace398653e16a0d`, 프런트는 `dev@015f333aa2b2d4230560d69013136190b5ea2c87`을 대조했다.

## 현재 연결 상태

백엔드에 회원별 설정 저장·조회/변경·발송 정책 적용이 있다. 프런트의 `src`에는 이 설정 API 호출과 `waitingEnabled` 설정 연결이 없다. 매장 모델의 `reservationEnabled`는 매장의 예약 지원 여부이며 회원 수신 설정과 다르다.

고객 SSE 수신과 연결·재연결 후 데이터 재조회는 이미 있다. 실제 설정 화면·SSE 수신·사용자 시나리오를 실행한 결과는 아니다.

## API 계약

고객 자신의 설정이며 기존 `X-Member-Id`를 사용한다. 프런트의 공통 Axios 인터셉터가 이 헤더를 붙인다. JWT 연동 완료로 간주하지 않는다.

| 요청 | 성공 응답과 의미 |
| --- | --- |
| `GET /api/user/members/me/notification-settings` | 200, 현재 설정. 저장 이력이 없으면 두 값 모두 `true` |
| `PUT /api/user/members/me/notification-settings` | 200, 저장 후 설정. 같은 값 재요청도 성공 |

PUT 본문과 두 API의 응답 필드는 다음 두 Boolean이다. PUT에서는 둘 다 필수이며 `null`이나 누락은400이다.

```json
{
  "reservationEnabled": true,
  "waitingEnabled": false
}
```

회원 식별 헤더 누락·형식 오류는401, 고객이 아닌 회원은403, 존재하지 않는 회원은404다. 설정 갱신 성공은 응답으로 확인하며 실패를 저장 완료로 표시하지 않는다.

## 선택 설정과 필수 알림

| 알림 | 적용할 설정 |
| --- | --- |
| 예약 확정·변경 | `reservationEnabled` |
| 웨이팅 순번 변경 | `waitingEnabled` |
| 호출·취소·만료·점주 대기 목록 갱신 | 설정과 무관한 필수 알림 |

선택 설정을 꺼도 호출·취소·만료 안내는 유지한다. 설정은 이후 발송 여부에 적용하며, 이미 받아 둔 알림 목록을 삭제하는 API는 아니다. 알림톡 PR #251도 공통 필수 호출 정책을 재사용한다.

웨이팅 순번 SSE를 껐을 때도 조회 API는 사용할 수 있다. 화면 진입·새로고침·SSE 재연결 후 기존 데이터 재조회 흐름을 유지한다. 프런트 SSE 구독 전체를 끊으면 필수 알림도 받지 못하므로 두 Boolean을 전체 구독 여부로 사용하지 않는다.

## 프런트 담당에게 넘길 작업

- 설정 조회·변경 API 함수와 상태 처리를 연결한다. 실제 회원의 GET 결과를 기준으로 표시한다.
- 저장 응답을 표시 값에 반영하고, 저장 실패를 사용자에게 안내한다.
- 선택 알림을 꺼도 필수 호출이 유지되고, 재연결 시 데이터가 갱신되는지 실제 환경에서 확인한다.

근거: [백엔드 컨트롤러](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/484daf3aa88913f382baf6468ace398653e16a0d/src/main/java/com/catchtable/notification/controller/UserNotificationSettingController.java), [발송 정책](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/484daf3aa88913f382baf6468ace398653e16a0d/src/main/java/com/catchtable/notification/entity/MemberNotificationSetting.java), [프런트 SSE](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/notification/hooks/useNotificationStream.ts), [연결 후 재조회](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/app/providers/RealtimeNotifications.tsx).

이번 변경은 소스 대조와 문서 작성이다. 앱 테스트를 새로 실행하거나 프런트 코드·기존 정책을 변경하지 않았다. 실제 화면·공유 DB 적용·Toss·SOLAPI 실발송·배포는 확인하지 않았다.

## 웨이팅 SSE 전달 보완 — 2026-11-11

웨이팅 업무와 알림 작업을 함께 저장하고 전용 worker가 비동기로 전달하므로 HTTP 응답보다 알림이 늦을 수 있다. 이벤트명·payload·수신 설정 정책과 재연결 후 재조회 계약은 유지한다. 용량 초과 시 관련 업무는 rollback되고 `503 WAITING_SSE_CAPACITY_EXCEEDED`와 `Retry-After: 30`을 반환한다. 재시도 전에 현재 상태를 조회하며, 고객 수신 보장이나 과거 이벤트 재생으로 해석하지 않는다. [동작·운영 계약](../waiting-sse-durability.md)을 참고한다.
