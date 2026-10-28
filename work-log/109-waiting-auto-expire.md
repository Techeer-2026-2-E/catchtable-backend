# 웨이팅 호출 후 기한 자동 만료

- 작업일: 2026-10-28
- 관련 이슈: [#109 호출 미응답·미착석 자동 만료](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/109)
- 기준: `dev@cc95faf`, Java 21, PostgreSQL 16 (로컬 Compose).

## 배경과 목표

점주가 호출한 팀이 호출 기한(`call_expires_at`)까지 응답·입장하지 않으면 시스템이 순번을 종료한다. 종료 상태·사유는 [미응답·노쇼 종료 상태](../docs/development-decisions.md#미응답노쇼-종료-상태--확정) 결정대로 `NO_SHOW` + `AUTO_EXPIRED`이고, 고객 SSE 이벤트명은 `WAITING_EXPIRED`를 유지한다.

## 판단과 근거

2026-10-28 사용자 결정:

- 자동 만료의 처리자는 시스템이다. `waiting_end_history.actor_id`를 nullable로 바꾸고 자동 만료 이력은 `null`로 남긴다. 기존 기록의 "처리자를 남긴다"는 사람이 처리한 종료에 해당한다.
- 테스트에서는 웨이팅 스케줄만 끈다. 예약 스케줄과 공용 `CatchtableApplication`은 바꾸지 않는다.
- 고객 알림 문구: "호출 후 응답·입장 기한이 지나 웨이팅이 종료되었습니다."

잠금은 [동료 기능과 연결할 지점](../docs/development-decisions.md#동료-기능과-연결할-지점)의 행 잠금 방식을 따른다. 만료 대상 id를 먼저 조회한 뒤 건별 트랜잭션에서 웨이팅 행만 `for update`로 잠그고 상태·기한을 다시 확인한다. 매장 행은 잠그지 않아 신청의 매장 락과 교착되지 않는다. 조건부 일괄 `UPDATE`는 이력 저장과 건별 알림이 어려워 쓰지 않았다.

Notion 기능명세·API·ERD는 작업 환경의 Notion 연결에서 열리지 않아(404) 코드·저장소 문서만 대조했다.

## 실제 변경

- `V18__make_waiting_end_history_actor_nullable.sql`: `actor_id DROP NOT NULL`. 이미 적용된 V12는 수정하지 않았다. `WaitingEndHistory.actorId`의 `nullable = false`를 제거했다.
- `Waiting.expire(now)`: `CALLED`/`CONFIRMED`이고 `callExpiresAt < now`일 때만 종료하고 이력을 반환한다. 그 밖(기한 전·정확히 기한 시각·재호출로 연장·착석·취소·노쇼)은 예외 없이 `null`이다. 정확히 기한 시각에는 응답(`respond`)이 허용되는 것과 경계가 같다.
- `WaitingExpiryScheduler.expireCalledWaitings()`: `waiting.expiry-interval-ms`(기본 60초) 간격, 주입된 `Clock` 기준. 건별 `TransactionTemplate` 트랜잭션이며 한 건 실패는 로그만 남기고 다음 건을 처리한다. 만료 건수를 반환한다.
- 만료된 건만 커밋 후 알림: 고객 `WAITING_EXPIRED`(targetId 웨이팅 ID), 점주 `WAITING_CHANGED`(targetId 매장 ID), `WaitingQueueChangedEvent`로 같은 매장·날짜 남은 고객의 순번 갱신.
- 점주 노쇼 처리로 `AUTO_EXPIRED`를 `NO_SHOW`/`NO_RESPONSE`로 정정하는 기존 흐름은 그대로다.

참고: 현재 `respond()`는 상태를 `CALLED`로 유지하고 `respondedAt`만 채우므로, 도착 응답한 팀도 같은 `callExpiresAt`로 만료된다. 점주 `enter()`는 기한이 지나도 만료 전이면 입장 처리할 수 있다.

## 검증

| 확인한 내용 | 실제 명령 또는 방법·환경 | 결과·근거 |
| --- | --- | --- |
| 엔티티 조건·경계 시각 | `WaitingExpireTest` | 6개 통과 |
| 기한 지난 건만 1회 만료, 이력 `actor_id` null, 이벤트, 재실행 무변화, 재호출 연장, 다른 매장·날짜 무영향 | `WaitingExpirySchedulerTest` (고정 Clock 2030-01-11 00:05 KST) | 3개 통과 |
| 자동 만료와 점주 입장 동시 실행 시 한쪽만 반영 | `WaitingAutoExpireConcurrencyTest` | 1개 통과 |
| 전체 빌드 | `DB_HOST=localhost DB_PORT=5433 DB_NAME=catchtable_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew build` | 성공, 332개 테스트 실패·건너뜀 0 |

`catchtable_test`는 이번에 새로 만든 격리 DB다. 개발 DB에는 V18을 적용하지 않았다. CI·프런트 연동·배포는 확인하지 않았다.

## 남은 점과 후속 작업

- 자동 만료 테스트는 시작 시 DB에 남은 만료 대상을 먼저 처리한 뒤 건수를 검증한다. 테스트 전용 DB에서만 실행한다.
- 스케줄은 단일 서버 기준이다. 다중 인스턴스에서도 행 잠금으로 중복 종료·알림은 막히지만, 같은 대상을 여러 서버가 조회한다.
- Notion API 명세에 자동 만료 이력의 `actor_id = null`과 알림 문구를 반영해야 한다(외부 문서는 수정하지 않았다).
