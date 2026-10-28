# catchtable-backend

Techeer 2026-2 E팀 

레스토랑 예약·웨이팅 서비스를 만드는 Java 21 / Spring Boot 백엔드다.

## 처음 읽을 문서

| 목적 | 문서 |
| --- | --- |
| 구성·실행·검증·Notion 정본·브랜치 기준 | [프로젝트 기준](docs/PROJECT.md) |
| Codex·Claude Code 공통 작업 규칙 | [AGENTS.md](AGENTS.md) |
| 팀원의 도구 준비와 작업 순서 | [AI 협업 안내](docs/ai-workflow.md) |
| 변경 이유와 검증 근거 | [작업 기록](work-log/README.md), [기존 개발 의사결정 기록](docs/development-decisions.md) |
| 문서 차이와 결정·확인 경로 | [확인할 사항](docs/OPEN_ITEMS.md) |

## 작업 흐름

1. Notion 명세와 이슈에서 문제·범위·완료 조건을 확인한다. AI 요청에는 [작업 지시 양식](docs/templates/task-brief.md)을 사용할 수 있다.
2. 관련 코드·테스트·기록을 읽고 변경한다. 앱 테스트는 [격리된 테스트 DB](docs/PROJECT.md#검증)에서 실행한다.
3. [PR 양식](.github/pull_request_template.md)에 결과와 검증을 적는다. 중요한 변경은 [work-log](work-log/README.md)도 연결하고, 작은 수정은 PR 본문으로 마친다.

요구사항은 Notion, 구현은 현재 코드, 진행 상태는 이슈·PR에서 확인한다. 선택 CI와 개인 메모 양식은 [선택 항목](docs/ai-workflow.md#선택-항목)에 있다.

## 로컬 예약 API 확인

고정 목 계정은 고객 `id=1`, 점주 `id=2`를 사용한다. 운영·CI에는 목 데이터를 자동으로 넣지 않는다.

```bash
docker compose up -d
./gradlew bootRun
```

애플리케이션이 시작되어 Flyway 적용이 끝나면 다른 터미널에서 목 데이터를 넣는다.

```bash
docker compose exec -T postgres psql -U catchtable -d catchtable < scripts/local-seed.sql
```

목 매장은 `id=1`이고 매일 09:00~22:00에 운영한다. 2인·4인·6인 테이블이 하나씩 생성된다. 같은 목 데이터에는 재실행할 수 있다. 기존 계정·매장·테이블·영업시간이 목 데이터와 충돌하면 전체 삽입을 롤백하므로, 기존 데이터를 유지하고 별도의 빈 로컬 DB에서 실행한다.

## 점주 웨이팅과 예약 선점·변경

정책 선택, 프런트 연동 계약, 팀 논의 대기 항목은 [개발 의사결정 기록](docs/development-decisions.md)에 모았다.

- 점주 대기 목록: `GET /api/owner/stores/{storeId}/waitings`. `X-Member-Id`로 점주 유형·소유권을 확인한다. 당일 활성 팀과 인원이 맞는 테이블 목록을 반환한다.
- 목록 SSE: `GET /api/owner/stores/{storeId}/waitings/subscribe?memberId={ownerId}`. `connect`와 `WAITING_CHANGED` 이벤트를 받으면 목록을 재조회한다.
- 점주 취소: `DELETE /api/owner/waitings/{waitingId}`, 본문 `{"reason":"매장 사정"}`. 미응답·노쇼: `POST /api/owner/waitings/{waitingId}/no-show`, 본문 `{"reason":"NO_RESPONSE","detail":"호출 후 응답 없음"}`. 자세한 종료 계약은 의사결정 기록을 참고한다.
- 점주 취소·미응답·노쇼 종료가 커밋되면 같은 매장·날짜의 남은 고객에게 `WAITING_POSITION_CHANGED` SSE를 보낸다. `payload`에 `waitingId`, `currentPosition`, `teamsAhead`, `status`를 담으며, 종료 재요청이나 사유만 정정한 경우에는 다시 보내지 않는다.
- 선점 생성: `POST /api/user/reservations/holds`, 본문은 기존 예약 생성과 같은 `storeId`, `reservationStartAt`, `partySize`. 본인 선점 조회·해제: `GET`, `DELETE /api/user/reservations/holds/{holdId}`. 모두 `X-Member-Id`를 받는다.
- 선점은 `PENDING_PAYMENT` 예약 자체다. `holdId`와 `reservationId`가 같고, 생성 시각부터 5분인 `expiresAt`과 `serverTime`, `remainingSeconds`를 반환한다. 조회·재접속으로 기한이 연장되지 않는다. 명시적 해제는 `CANCELLED`, 시간 만료는 `EXPIRED`다. 반환된 테이블은 다시 예약할 수 있고 무료 예약은 즉시 확정된다.
- 예약 변경: `PATCH /api/owner/reservations/{reservationId}`, `X-Member-Id`와 본문 `{"reservationStartAt":"2026-10-20T18:00:00+09:00","partySize":4}`. 한 필드만 보내도 된다. 새 조건에 맞는 테이블 확보와 예약 변경이 함께 커밋되며, 실패하면 기존 예약을 유지한다. 성공하면 고객에게 `RESERVATION_CHANGED` SSE를 보낸다.

예약 변경은 미입장 확정 예약에 한정한다. 인원만 변경하면 기존 시작·종료 시각을 유지하고, 시간을 변경하면 매장 정책을 만족하는 미래 시각이어야 한다. 두 경우 모두 수용 가능한 빈 테이블을 확보해야 변경되며, 실패하면 기존 예약을 유지한다. 판단과 검증은 [기능37 작업 기록](work-log/66-post-start-reservation-change.md)에 남겼다. 결제 승인·결과 재확인·환불은 아직 연결하지 않았으며, 공통 결제 계약이 정해지면 승인 중/결과 불명인 선점의 자동 만료와 명시적 해제를 함께 연동해야 한다.

마이그레이션은 `V12`(웨이팅 종료 이력), `V13`(호출 도착 제한시간), `V14`(웨이팅 호출), `V15`(예약 선점) 순서다. 예약 선점은 새로 반영된 호출 기능과 번호가 겹치지 않도록 기존 V13에서 V15로 옮겼다.

예약의 최초 예약금액·환불 정책 보관은 `V20`과 생성 시 입력하는 스냅샷 필드로 준비했다. 무료 즉시 확정은 금액 0·정책 없음으로 기록한다. 기존 행·정책 미연결 선점은 금액을 추정하지 않으며, 유료 스냅샷 생성 경계는 매장 설정 원본을 확인한 호출부가 금액·정책을 함께 전달해야 한다. 현재 선점 HTTP 생성은 아직 이 경계를 호출하지 않는다. [기능10 스냅샷 작업 기록](work-log/65-deposit-snapshot.md)에 공통 기능58 의존성과 미연결 범위를 남겼다.

## 점주 예약 취소 알림

점주가 예약을 취소하면 커밋 후 예약 고객에게 `RESERVATION_CANCELED` SSE 이벤트를 한 번 보낸다. `targetId`는 예약 ID이며 `message`에 취소 사유가 포함된다. 같은 취소를 재요청해도 알림을 다시 보내지 않는다.

클라이언트는 알림 수신 또는 SSE의 `connect` 이벤트 수신 후 `GET /api/user/reservations/{reservationId}`를 호출해 최신 상태와 취소 사유를 갱신한다. 기존 P1 고객 API처럼 `X-Member-Id` 헤더를 사용하며 본인 예약만 조회할 수 있다.

## 취소 시점별 환불금 계산

기능61의 환불 계산기는 예약 당시 금액·정책과 변경 후 방문일을 기준으로 원 미만을 버린 뒤 기환불액을 차감한다. 점주 취소는 원결제액을 총 환불 한도로 사용한다. `GET /api/user/reservations/{reservationId}/refund-preview`는 `X-Member-Id`로 본인 예약을 확인하고 시작 전·미입장·확정 예약의 고객 취소 예상액을 반환한다. 무료 예약은 0원이며, 유료 금액·정책·승인 원장이 불완전하면 `409 REFUND_DATA_NOT_READY`다. 조회는 실제 취소·환불을 실행하지 않는다. 이 API는 기능10 스냅샷과 미병합 기능58 PR #144에 의존하며, 유료 생성·실제 Toss 취소는 아직 연결하지 않았다. [기능61 작업 기록](work-log/67-refund-calculation.md)에 판단·검증·연동 조건을 남겼다.
