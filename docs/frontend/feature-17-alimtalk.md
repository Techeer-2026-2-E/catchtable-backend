# 기능17 호출·재호출 알림톡 연동 안내

확인일: 2026-11-05. 구현은 [기능 PR #251](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/251)의 `12da56c5` 기준이다. 이 문서 작성 시 기능 PR은 리뷰 전이며, `dev`에 구현이 포함됐는지는 해당 PR의 머지 상태로 확인한다. 문서 머지가 기능 머지나 배포를 뜻하지 않는다.

정본은 [기능17](https://app.notion.com/p/3d548d76fa3281dba92edea0ce84ce29), [수신 설정](https://app.notion.com/p/3d548d76fa328174ac06d1133438dbbb), [웹훅 API](https://app.notion.com/p/40e48d76fa3281b9ae5effd0c43b46b3)다. 구현 판단과 검증은 [작업 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/12da56c5daba9f9405d336941dc90b24cf503691/work-log/250-waiting-alimtalk.md)에 남긴다.

## 프런트에서 유지할 계약

| 항목 | 계약과 의미 |
| --- | --- |
| 최초 호출 | `POST /api/owner/waitings/{waitingId}/call` |
| 재호출 | `POST /api/owner/waitings/{waitingId}/recall` |
| 회원 식별 | 기존 `X-Member-Id` 헤더와 점주 소유권 검사 유지 |
| 호출 성공 응답 | 업무 처리 성공이다. 알림톡 최종 수신 성공을 뜻하지 않는다 |
| 고객 SSE | 기존 `WAITING_CALLED` 이벤트·payload와 호출 기한을 유지한다 |
| 수신 설정 | 호출은 필수 알림이므로 `waitingEnabled=false`여도 발송 요청을 저장한다 |

프런트 코드나 기존 API 응답에 발송 결과 필드를 추가하지 않았다. 발송 결과를 조회하는 고객·점주용 API도 이번 범위에 없다. 프런트는 기존 상태 재조회·SSE 처리와 호출 기한 표시를 유지한다.

호출과 같은 트랜잭션에서 발송 요청을 저장하며, 롤백하면 요청도 사라진다. 동일 `(waiting_id, call_count, recall_count)` 이벤트는 한 번만 저장한다. 기존 API가 수락한 별도 호출은 별도 요청이며, HTTP 요청용 멱등키를 새로 제공하지 않는다.

## 내부 발송 상태와 복구

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 최초 발송 또는 확정된 일시적 실패의 재시도 대기 |
| `PROCESSING` | 한 작업자가 발송 권한을 얻은 상태 |
| `ACCEPTED` | 공급사 접수 확인. 최종 수신은 미확인 |
| `UNKNOWN` | 응답 유실·해석 실패·처리 중단으로 결과 불명 |
| `SENT` | 공급사 성공 보고 확인. 모의 모드에서는 합성 성공 |
| `FAILED` | 영구 실패 또는 최대 시도 횟수 도달 |
| `SKIPPED` | 발송 전에 호출 만료·종료·대체를 확인함 |

- 일시적 실패가 확정됐을 때만 최대 총3회 시도한다. 첫 실패 뒤30초, 두 번째 실패 뒤2분을 기다린다.
- `UNKNOWN`은 새 발송을 시작하지 않는다. 메시지ID가 있으면 조회하고, 인증 웹훅이 도착하면 결과를 반영한다. 메시지ID도 웹훅도 없으면 미확인 상태로 남는다.
- `PROCESSING`에서 프로세스가 중단되면60초 뒤 `UNKNOWN`으로 복구한다. 새 발송으로 바꾸지 않는다.
- 발송 직전에 호출 상태·현재 시각·호출 차수를 확인한다. 이미 외부에서 접수한 메시지는 호출 종료 후에도 결과를 확인한다. 접수 이후 실제 수신 시각과 정확히 한 번의 고객 수신은 보장하지 않는다.
- 모드도 요청에 저장한다. 모의 요청을 저장한 뒤 설정을 `solapi`로 바꿔도 기존 모의 요청을 실발송하지 않는다.

## 비용 없이 로컬 확인하기

기본값은 `ALIMTALK_ENABLED=false`, `ALIMTALK_MODE=simulation`이다. 기능 PR 브랜치 또는 그 머지를 포함한 브랜치에서 실행한다. DB는 [프로젝트 실행·검증 절차](../PROJECT.md#검증)에 따라 보존할 데이터가 없는 전용 DB를 준비한다.

```bash
DB_HOST=localhost DB_PORT=5433 DB_NAME=catchtable_alimtalk_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable \
ALIMTALK_ENABLED=true ALIMTALK_MODE=simulation ./gradlew bootRun
```

`simulation`은 SOLAPI HTTP 요청 없이 합성 결과를 저장한다. `.env`는 Compose용이므로 Gradle로 실행하는 앱에는 위처럼 환경변수를 전달한다. 기존 목 데이터 절차로 생성한 대기 건을 호출·재호출하고 전용 DB의 `alimtalk_outbox`를 확인할 수 있다. `SENT`만으로 실제 휴대전화 수신을 판단하지 않는다.

실제 HTTP 어댑터는 `ALIMTALK_MODE=solapi`로 선택한다. 이번 검증에서는 가짜 키와 loopback 모의 서버만 사용했다. 아래 변수들은 어댑터 설정이며 실제 계정 값을 저장소나 프런트에 넣지 않는다.

| 환경변수 | 용도 |
| --- | --- |
| `SOLAPI_BASE_URL` | 모의 검증에서는 `http://127.0.0.1:{모의서버포트}`. 기본값은 SOLAPI API 주소 |
| `SOLAPI_API_KEY`, `SOLAPI_API_SECRET` | 요청 HMAC 인증. 모의 서버에서는 가짜 값 사용 |
| `SOLAPI_CHANNEL_ID`, `SOLAPI_TEMPLATE_ID` | 채널·템플릿 식별자. 모의 서버에서는 가짜 값 사용 |
| `SOLAPI_WEBHOOK_SECRET` | 결과 웹훅 인증용 원문 secret. `solapi` 모드에서 필수 |

템플릿 변수는 `#{매장명}`, `#{대기번호}`, `#{입장기한}`이며 기한은 `Asia/Seoul`의 `HH:mm`이다. 실제 승인 템플릿과의 일치는 검증하지 않았다. SMS 대체 발송은 제공하지 않으며 HTTP 요청에 `disableSms=true`를 명시한다.

## 웹훅 계약

`POST /api/integrations/alimtalk/solapi`는 `ALIMTALK_ENABLED=true`일 때만 등록된다. 기본 비활성 상태에서는 이 경로가 없다.

- `X-Solapi-Secret`: 설정한 `SOLAPI_WEBHOOK_SECRET`을 SHA1으로 해시한 소문자16진수40자리. 원문 secret을 보내지 않는다.
- `X-Solapi-Event-Name: SINGLE-REPORT`
- JSON 본문: 보고 객체의 배열. `messageId`, `type=ATA`, `to`, `statusCode`, `customFields.outboxId`, `customFields.attempt`를 대조한다. `to`는 발송 요청의 공백·하이픈을 제거한 번호와 같아야 한다.
- 인증 실패는401, 인증된 `SINGLE-REPORT`의 배열이 아닌 본문은400, 수락은 본문 없는200이다. 다른 이벤트나 이 서비스 요청과 맞지 않는 보고는 결과를 변경하지 않고200을 반환한다.
- 이전 차수·다른 수신자·다른 메시지ID·모의 모드 요청의 보고는 반영하지 않는다. 중복 보고나 늦은 실패로 최종 성공을 덮어쓰지 않는다.

웹훅 주소를 공급사 계정에 등록하거나 외부에 앱을 배포하지 않았다. 로컬 모의 HTTP 테스트가 이 계약을 검증한다.

## 확인한 검증 범위

기능 PR의 전용 PostgreSQL16 DB에서 전체84클래스915개가 실패·오류·건너뜀0으로 통과했고 `bootJar`를 만들었다. 관련3클래스54개는 그 전체에 포함된다. 공개 호출·재호출 API → DB 요청 → 실제 `RestClient` → loopback 모의 SOLAPI → 인증 웹훅 → 최종 기록도 확인했다.

```bash
DB_HOST=localhost DB_PORT=5433 DB_NAME=catchtable_alimtalk_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable TOSS_SECRET_KEY= ALIMTALK_ENABLED=false \
./gradlew test --tests 'com.catchtable.notification.client.RestSolapiAlimtalkGatewayTest' \
  --tests 'com.catchtable.notification.service.AlimtalkDeliveryTest' \
  --tests 'com.catchtable.notification.service.AlimtalkHttpFlowTest'
```

위 결과는 기능 PR 코드의 로컬 검증이다. 이 문서 PR에서 앱 테스트를 다시 실행한 결과나 원격 CI 결과로 바꿔 읽지 않는다. 실제 SOLAPI 계정·승인 템플릿·사용자 수신·공유 DB 적용·프런트 통합·배포는 확인하지 않았다.

## 프런트 원격 dev 대조 — 2026-11-05

프런트 기준은 `dev@015f333aa2b2d4230560d69013136190b5ea2c87`, 백엔드 기준은 `dev@484daf3aa88913f382baf6468ace398653e16a0d`와 알림톡 PR #251의 `12da56c5`다. 소스 대조 결과이며 화면을 실행한 결과는 아니다.

| 항목 | 확인한 연결 | 남은 연결 |
| --- | --- | --- |
| 최초 호출 | 점주 API의 `/call`, mutation과 호출 버튼이 있다 | 알림톡 최종 수신을 호출 성공 응답으로 표시하지 않는다 |
| 고객 SSE | `WAITING_CALLED` 수신·토스트·예약/웨이팅 재조회 및 재연결 시 재조회가 있다 | 실제 실행 환경에서 수신·화면 갱신 확인 |
| 점주 SSE | `WAITING_CHANGED`와 `connect` 때 목록 재조회가 있다 | 실제 실행 환경에서 목록 갱신 확인 |
| 호출 재전송 | 백엔드 `/recall`과 응답 DTO가 있다 | 프런트 API 함수·mutation·버튼은 아직 없다 |

기한을 유지하는 재전송은 `POST /api/owner/waitings/{waitingId}/recall`을 연결한다. 본문 없이 `X-Member-Id`를 전달한다. `CALLED`이며 만료 전일 때200, 그 외409 `WAITING_NOT_RECALLABLE`이다. 응답은 기존 호출 필드와 `recallCount`, `lastRecalledAt`이며 원래 `expiresAt`은 유지한다. `/call`은 호출 시각과 기한을 새로 정하므로 재전송 버튼에 대신 연결하면 동작이 달라진다.

근거: [점주 API](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/owner-waiting/api/ownerWaitingApi.ts), [점주 mutation·SSE](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/owner-waiting/hooks/useOwnerWaitings.ts), [고객 SSE](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/notification/hooks/useNotificationStream.ts), [알림 수신 후 재조회](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/app/providers/RealtimeNotifications.tsx), [백엔드 호출·재전송](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/484daf3aa88913f382baf6468ace398653e16a0d/src/main/java/com/catchtable/waiting/service/OwnerWaitingService.java).

실제 SOLAPI 키나 웹훅 secret은 프런트에 전달하지 않는다. 발송과 결과 확인은 백엔드가 담당한다. 이번 대조에서 프런트 코드·목 서버·API·정책을 변경하지 않았다.

## 호출·재전송의 SSE 수용·전달 계약 — 2026-11-12

`POST /api/owner/waitings/{waitingId}/call`과 `/recall`은 기존 `X-Member-Id` 점주 유형·매장 소유권 및 상태 검사를 유지한다. `/call`의 기존 설명은 [기능44의 SSE 보완](feature-44-owner-waiting-list.md#웨이팅-sse-전달-보완--2026-11-11)에도 있다. `/recall`의 만료 전 CALLED 조건, 200 응답 필드·원래 기한/응답 유지와 409 `WAITING_NOT_RECALLABLE`은 그대로다.

업무 상태·V36 이력·SSE 작업을 같은 TX에서 저장한다. SSE 수용 한도에 걸리면 `503 WAITING_SSE_CAPACITY_EXCEEDED`, `Retry-After: 30`을 반환하고 해당 호출/재전송 TX 전체가 rollback된다. 호출 차수·재전송 횟수·기한 변경과 알림톡 enqueue 등 이 TX의 변경도 함께 취소된다. 보존 한도는 완료 이력까지 포함하므로 30초 뒤 자동으로 용량이 풀린다는 뜻이 아니다.

503을 받으면 현재 상태·호출 차수·기한을 재조회하고 유효한 동작인지 확인한 뒤 안전하게 재시도한다. HTTP 응답 유실은 실패 확정이 아니며, `/call`을 무조건 다시 보내면 새 차수·시각·기한이 생긴다. `/recall`도 수락할 때마다 새 재전송 사건과 누적 횟수를 만들므로 HTTP 멱등 재요청으로 취급하지 않는다.

업무 commit 뒤 전용 worker가 SSE를 비동기로 전달하므로 성공 HTTP보다 알림이 늦을 수 있다. 전송 실패로 이미 commit한 업무를 되돌리지 않으며, 호출 성공을 알림톡 최종 전달·고객 수신으로 표시하지 않는다. SSE 수신·재연결 후에는 현재 상태를 재조회한다. [현행 전달·용량·UNKNOWN 계약](../waiting-sse-durability.md)을 따른다.
