# 확인할 사항

이 문서는 미결정·불일치의 원본으로 가는 안내다. 이슈·Notion에 있는 진행 상태나 기능 목록을 복제하지 않는다. 이슈가 없는 중요한 차이를 발견했을 때만 확인일·근거·다음 행동을 남긴다.

## Git·코드컨벤션 문서 차이 — 2026-10-27 확인

- [팀 Git 규칙](https://app.notion.com/p/bf748d76fa32835d8c0001bcac5895ea)은 `dev`와 `<type>: <제목>`을 기준으로 한다. 원격 기본 브랜치와 [기존 CI](../.github/workflows/ci.yml)도 `dev`를 사용한다. 2026-10-27 원격 갱신과 GitHub API 조회에서 `main`·`dev`의 보호 미설정을 확인했다.
- 오래된 [코드컨벤션](https://app.notion.com/p/00048d76fa32825bbe8981f039abbca6)에는 `develop`, `snake_case`, `except` 등 현재 Java 저장소와 맞지 않는 설명이 남아 있다.
- 이번 로컬 지침은 팀 Git 규칙과 기존 Java 코드의 이름·구조를 안내한다. 외부 Notion 원문은 변경하지 않았다. 팀에서 코드컨벤션을 Java와 현재 브랜치 기준으로 정리할 때 이 항목을 해결한다.

## 기존에 논의·연결할 항목

| 주제 | 원본·근거 | 다음 확인 |
| --- | --- | --- |
| 결제 승인·결과 불명·환불과 선점 해제/만료 연결 | [선점 구현의 경계](development-decisions.md#선점-구현의-경계), [Notion API](https://app.notion.com/p/5cb48d76fa3283e7b711010662ec4e75) | 관련 작업에서 공통 결제 계약·코드·테스트 확인 |
| 프런트 계약·화면 연동 | [기존 의사결정 기록](development-decisions.md), [API 정본](https://app.notion.com/p/5cb48d76fa3283e7b711010662ec4e75) | 변경한 API·이벤트의 실제 호출부와 화면 확인. 백엔드 테스트 통과와 구분 |
| 웨이팅 대기표 응답과 전환 기준 (2026-11-22) | [기존 신청 API](https://app.notion.com/p/fbf48d76fa3282e1a946818c8cba1f3d), [신규 대기표 계약](frontend/waiting-receipt-lifecycle.md), [검증 기록](../work-log/2026-11-22-redis-product.md) | 기존 201·200·409 위에 활성 매장의 202·본인 표 조회·취소가 추가된다. 팀 리뷰에서 계약과 매장 확대 기준을 확정한 뒤 정본에 반영한다. |
| GitHub 보호 규칙·필수 CI | [프로젝트 협업 기준](PROJECT.md#협업-기준) | `main`·`dev` 보호 미설정 확인. 팀의 리뷰·CI 기준을 GitHub에서 강제할지는 후속 팀 결정 |

## Redis·알림 머지 뒤 정본과의 차이 — 2026-11-23 확인

dev `c62ce9b` 기준이다. Notion은 API·ERD가 11-06, 웨이팅 신청·예약 가능 시간이 11-05에 마지막으로 수정됐다. 코드에 맞춰 정본을 고치지 않았다. 팀 리뷰에서 계약을 확정한 뒤 정본과 프런트 인계에 반영한다.

| 주제 | 정본 | 현재 코드 | 다음 행동 |
| --- | --- | --- | --- |
| 대기표 매장의 웨이팅 신청 | [웨이팅 신청](https://app.notion.com/p/fbf48d76fa3282e1a946818c8cba1f3d): `X-Member-Id`만 필요, 신규 201·`WAITING` | `Idempotency-Key` 필수(없으면 400), 주로 202와 대기표 본문. 본문 `status`는 대기표 상태, 실제 웨이팅 상태는 `waitingStatus`. 409 `WAITING_RECEIPT_KEY_CONFLICT`, 429 `WAITING_INTAKE_FULL`, 대기표 조회·취소 API 추가 | 위 '웨이팅 대기표 응답과 전환 기준'과 함께 확정 |
| 웨이팅 신청의 503 | 오류표에 없음 | DB 일시 오류는 기존 매장도 `503 WAITING_RECEIPT_UNCONFIRMED`와 `Retry-After` | 오류표 반영 여부 결정 |
| 영업시간 밖 신청 | 같은 페이지: 영업시간 검사 미연결 | 409 `WAITING_OUTSIDE_BUSINESS_HOURS` (#267) | 정본 갱신 |
| 예약 가능 시간 | [예약 가능 시간](https://app.notion.com/p/b2e48d76fa3283e9955581490b50dc86): 재고의 순간값 | 캐시를 켜면 최대 2초 전 값과 503 `AVAILABILITY_BUSY`. 기본 꺼짐 | 켜기 전 신선도 허용 범위 결정 |
| 알림함 | [알림 목록 조회](https://app.notion.com/p/91348d76fa328232a106017969bcdb47): 미구현, 계획 경로 `PATCH /{id}/read`·`/read-all` | `GET`(lastId 커서), `GET /unread`, `PATCH /read`(lastId, 204), `DELETE /{id}` (#291) | 경로·응답 확정 후 정본 갱신 |
| 상태 변경 알림 | [상태 변경 알림](https://app.notion.com/p/3d548d76fa328150ac27dcf66783c63f): 영속 알림함 없음, 중복 표시 방지 | 알림함에 저장. 쓰기를 확정하지 못하면 같은 이벤트 ID로 다시 보낼 수 있음 | 프런트의 이벤트 ID 중복 제거 확인 |
| 접수 종료 뒤 번호 | [접수 시작·종료](https://app.notion.com/p/3d548d76fa328144bb26c6a2c77c45de): 종료 중 신규 신청 차단 | 신규는 차단. 종료 전에 받은 대기표는 종료 뒤에도 번호를 받음 | 정책 결정 |
| ERD | [ERD](https://app.notion.com/p/40748d76fa32839aa437011845ab7f7b): V34까지 | V35~V55 미반영(대기표·장부·캐시·알림 10개 포함). V38·V39 옛 SSE 원장 테이블은 남아 있으나 코드가 읽지 않음 | ERD 갱신, 옛 테이블 정리 시점 결정 |
| health | 없음 | `/actuator/health`의 `redis` 항목과 `DEGRADED`(200) (#301) | 운영 확인 기준에 반영 |

## Redis를 운영에서 켜기 전 — 2026-11-23

모든 Redis 기능은 기본 꺼짐이다. 켜기 전에 아래를 정한다.

| 주제 | 근거 | 다음 행동 |
| --- | --- | --- |
| 호스트 구성 | [Redis 제품 경로](redis-product.md#호스트-구성)는 3호스트 Sentinel, 현재 배포는 EC2 한 대 | 서버 사양과 배치 결정 |
| 전송 암호화 | #297 리뷰(Redis·Sentinel 인증값 평문 전송) | 앱은 `rediss://` 주소를 받는다. 인프라 TLS 구성 결정 |
| 등록 허가 공유 | `waiting_slots_required`(V49): 한 매장이 켜지면 전 매장 등록이 허가 2개를 공유. 개점 측정에서 다른 매장 회복은 8~16초였고 이 공유의 영향은 따로 나누지 않음 | 매장 단위로 나눌지 재측정 후 결정 |
| 재측정 | #299 묶음 잠금 상한 5초, #301 Redis 장애 대체 경로 | 개점 부하로 다시 측정 |
| 측정 도구 보관 | `tools/`(Python·Go 약 6천 줄), `work-log/assets`, CI의 도구 테스트. #297 리뷰 9건이 도구 대상 | 보관하면 수정, 아니면 별도 위치로 이동 |
| 사후 리뷰 | #291 #295 #297 #298 #299~#303이 승인 없이 머지됨 | 팀 리뷰 |

새 항목에는 확인일·대상 브랜치/커밋, 확인한 사실과 근거, 모르는 점, 영향받는 작업, 다음 행동·이슈 링크를 적는다. 해결되면 결정은 정본에, 실제 변경·검증은 PR 또는 work-log에 남기고 이 문서의 임시 항목은 정리한다. 기존 기록을 과거 사실과 다르게 덮어쓰지 않는다.
