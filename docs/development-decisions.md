# 개발 의사결정 기록

2026-10-11. P1 점주 웨이팅 기능 44·49·50의 정책과 연동 계약을 팀에 공유하기 위한 기록이다.

## 점주 대기 목록 실시간 갱신 — 확정

- 결정: SSE를 사용한다. 사용자 확인: “sse로 해”.
- 대안: 주기적 폴링. 구현은 단순하지만 갱신 지연과 반복 조회가 생긴다.
- 구현: 기존 회원별 SSE 연결 및 커밋 후 알림 전달을 재사용한다. `WAITING_CHANGED` 이벤트의 `targetId`는 매장 ID다. 고객 정보는 이벤트에 담지 않고, 프런트가 해당 매장의 목록을 다시 조회한다.
- 구독: `GET /api/owner/stores/{storeId}/waitings/subscribe?memberId={ownerId}`. 기존 EventSource 방식에 맞춘 임시 회원 식별이며, 점주 유형과 매장 소유권을 검사한다. 인증 도입 시 회원 ID 입력을 인증 세션으로 교체한다.
- 최초 `connect` 이벤트와 재연결 후에도 목록을 재조회한다. 이벤트 재생은 제공하지 않는다. 기존 메모리 SSE 저장소를 사용하므로 여러 서버 인스턴스로 확장할 때 전달 구조를 바꿔야 한다.
- 목록은 `GET /api/owner/stores/{storeId}/waitings`, `X-Member-Id` 헤더로 조회한다. 한국 시간의 당일 진행 중 팀을 대기번호 순서대로 반환한다.
- 프런트 배열 형식과 `id`, `waitingNumber`, `partySize`, `customerName`, `createdAt` 이름을 따른다. `compatibleTables`는 사용 가능한 설정의 테이블 중 인원 범위가 맞는 테이블이다. 실제 빈자리 여부는 점주가 확인한다.

## 미응답·노쇼 종료 상태 — 확정

- 프런트 `dev`의 `WaitingStatus`에는 `NO_SHOW`가 있고 `EXPIRED`, `CONFIRMED`는 없다. 점주 화면의 “미응답 노쇼”와 고객 화면의 호출 시간 만료 모두 `NO_SHOW`로 처리한다. 현재 두 화면은 목업 상태다.
- 결정: 사용자 확인 “백엔드도 NO_SHOW로 통일”. 백엔드 상태를 `EXPIRED`에서 `NO_SHOW`로 바꾼다.
- 대안: `EXPIRED`를 유지하고 프런트에서 변환하면 기존 백엔드 호출부 영향이 적다. 선택한 방식은 프런트와 종료 상태 이름이 같지만, 동료의 자동 만료 구현도 `NO_SHOW`로 맞춰야 한다.
- `CONFIRMED`는 고객의 도착 응답 상태여서 유지한다. 프런트는 진행 중 상태 판정에 이를 추가하거나 호출 화면으로 매핑해야 한다.
- 종료 사유는 `NO_RESPONSE`, `NO_SHOW`, `AUTO_EXPIRED`를 구분한다. 최초 종료 시각과 상태를 유지하며, 정정 전후 사유·설명·처리자·시각을 `waiting_end_history`에 남긴다. 같은 사유와 설명의 재시도는 이력과 알림을 중복 생성하지 않는다.
- 기존 `EXPIRED` 행은 `NO_SHOW`로 마이그레이션한다. 과거 상세 사유와 종료 시각은 알 수 없으므로 `LEGACY_EXPIRED`로 표시하고 시각은 임의로 채우지 않는다.
- 기존 고객 SSE 이벤트명 `WAITING_EXPIRED`는 유지한다. 이 이벤트를 받은 뒤 조회하면 상태는 `NO_SHOW`다. 이벤트명 변경은 호출 알림 담당자의 기존 계약에 영향을 주므로 이번 상태 변경에 포함하지 않는다.
- 프런트의 취소/노쇼 요청에는 아직 사유 입력이 없다. 기능 명세의 점주 취소 사유 및 사유 정정 이력을 위해 입력·요청 본문 연결이 필요하다.

## 동료 기능과 연결할 지점

- 호출·고객 응답·착석·고객 취소·자동 만료가 추가되면, 커밋 시 같은 `WAITING_CHANGED` 알림을 발행한다. 현재 개발 범위에서 신규 신청과 점주 종료가 발행 대상이다.
- 다음 팀은 점주가 수동 호출한다. 10월 6일 갱신된 기능 명세를 따르며, 이전 API 표의 자동 다음 호출 설명은 적용하지 않는다.
- 상태 변경은 웨이팅 행을 잠근 뒤 현재 상태를 확인한다. 자동 만료도 같은 행 잠금 또는 `WHERE status IN ('CALLED', 'CONFIRMED')` 조건부 갱신을 사용해야 한다. `version`은 늦게 저장되는 JPA 갱신이 확정 상태를 덮어쓰는 것도 방지한다.
- 호출 시각·타이머는 호출 담당 기능과 연결해야 한다. 이 변경은 호출 기능을 새로 구현하지 않는다.
- 리뷰 PR #79의 `V11` 다음으로 웨이팅 종료·이력 `V12`를 적용한다. 최신 `dev`의 호출 알림 PR #83도 포함하며, 새 알림 payload 형식을 유지한다.

## 점주 종료 API

- 취소: `DELETE /api/owner/waitings/{waitingId}`, 본문 `{"reason":"매장 사정으로 접수 종료"}`, 성공 `204`. 진행 중 상태만 취소하고 이미 취소된 요청은 재처리하지 않는다. 최초 취소 사유를 보존한다.
- 미응답·노쇼: `POST /api/owner/waitings/{waitingId}/no-show`, 본문 `{"reason":"NO_RESPONSE","detail":"호출 후 응답 없음"}`, 성공 `200`. `reason`은 `NO_RESPONSE` 또는 `NO_SHOW`, 설명은 공백이 아닌 300자 이하다. 호출/응답 상태를 종료하거나 기존 `NO_SHOW`의 사유를 정정한다.
- 성공 응답: `waitingId`, `status`, `reason`, `detail`, `endedAt`. 종료된 건의 정정으로 고객 알림이나 다음 팀 호출을 다시 실행하지 않는다.
- 두 API 모두 `X-Member-Id`를 받고 점주 유형과 웨이팅의 매장 소유권을 검증한다.
- 프런트 취소 초안의 `POST .../cancel`은 API 명세의 `DELETE`로 변경하고 사유 본문을 추가해야 한다. 노쇼 요청에도 사유와 설명을 추가한다. 현재 프런트 화면은 API와 연결되지 않은 목업이다.

## SSE 재접속 경합 수정

- 마지막 기존 연결 삭제와 재접속이 겹칠 때 새 연결이 저장소에서 누락되던 문제를 수정했다. 연결 목록 생성과 신규 연결 추가를 회원별 `ConcurrentHashMap.compute` 안에서 처리한다.

## 로컬 검증 결과

- 호출 알림 PR #83까지 포함한 `dev`(`07d7f2a`) 기준으로 P1 변경만 분리했다.
- 격리된 PostgreSQL 16에서 `./gradlew build --no-daemon`을 실행하여 전체 테스트 172개와 빌드가 통과했다. 실패·오류·건너뜀은 0개다. 빈 테스트 DB에 V1~V12가 적용됐고, 기존 `EXPIRED` 데이터의 변환도 별도로 검증했다.
- 점주 권한·매장 소유권, SSE 커밋/롤백, 재접속 경합, 중복 요청, 취소·노쇼·자동 만료 경합, 종료 사유 이력, 고객 내역의 `NO_SHOW` 필터, 최신 호출 알림 payload 계약을 검증했다.
- 프런트 실제 화면 연결은 검증하지 않았다. 종료 상태 마이그레이션을 배포할 때 호출·자동 만료 담당 코드도 `NO_SHOW`를 사용해야 한다.

## 참고 링크

- [대기 목록 기능 44](https://app.notion.com/3d548d76fa328152bfb5fd066d2d85e4)
- [미응답·노쇼 기능 49](https://app.notion.com/3d548d76fa3281d78299dca9955ab4f7)
- [점주 취소 기능 50](https://app.notion.com/3d548d76fa3281a98a5ac95e9dde8a3d)
- [프런트 상태 모델](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/dev/src/entities/waiting/model.ts)
- [점주 웨이팅 화면](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/dev/src/pages/owner/waitings/OwnerWaitingsPage.tsx)
- [고객 웨이팅 화면](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/dev/src/pages/waiting/WaitingPage.tsx)
- [점주 API 초안](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/dev/src/features/owner-waiting/api/ownerWaitingApi.ts)
- [고객 예약·웨이팅 내역 PR 76](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/76)
