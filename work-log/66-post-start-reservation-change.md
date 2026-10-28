# 기능37 — 시작 후 미입장 예약 변경

- 작업일: 2026-10-27
- 관련 이슈: [#66 예약 변경](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/66)
- 정본: [기능37 명세](https://app.notion.com/3d548d76fa3281e4a6a8faccd4f22817)
- 기준: `dev@61aaf744eab5fbfb8024e1c2fe83b915124c4f41`, Java 21, PostgreSQL 16.

## 배경과 목표

기존 코드는 미입장 확정 예약도 시작 시각이 지나면 변경을 막았다. 인원만 바꿀 때도 신규 예약의 미래 시각 검사와 현재 이용시간 설정을 적용해 기존 방문 구간을 유지할 수 없었다.

## 판단과 근거

2026-10-27 사용자가 다음 기준을 결정했다.

- 시작 후에도 미입장 `CONFIRMED` 예약은 변경할 수 있다. 이미 입장했거나 종료·취소·결제 대기 상태인 예약은 변경하지 않는다.
- 인원만 변경하면 기존 시작·종료 시각을 유지한다. 매장의 이용시간이 이후 변경됐다는 이유로 기존 방문 구간을 바꾸지 않는다.
- 시작 시각을 바꾸면 현재보다 미래여야 하며 현재 매장의 예약 간격·영업시간·예약 가능 기간·마감 규칙을 만족해야 한다.
- 인원·시간 변경 모두 수용 가능한 빈 테이블을 확보해야 한다. 목적 시간이 예약이나 선점으로 꽉 찼다면 변경을 거부하고 기존 예약을 보존한다.

검토한 경계는 기존 시작 전 제한 유지, 새 시각까지 과거 허용, 변경 후 미래 시각만 허용이었다. 사용자는 인원만 변경할 때 기존 시각 유지와 새 시각의 미래 제한을 선택했다. 이는 기존 [논의 기록](../docs/development-decisions.md#예약-시각이-지난-뒤의-예약-변경--팀-논의-대기)의 후속 결정이며 과거 기록은 보존한다. 외부 Notion 정본에는 이번 결정을 아직 반영하지 않았다.

## 실제 변경과 프런트 연동

- `Reservation.validateModifiable`에서 기존 시작 시각 제한을 제거한다. `changeSchedule`은 시작 시각이 실제로 바뀔 때 미래 시각을 검사한다.
- `OwnerReservationService`는 인원만 바꾸면 기존 구간으로 테이블을 재배정한다. 매장·예약·테이블 잠금과 DB 시간 중복 제약, 실패 롤백, 커밋 후 알림을 유지한다.
- API는 `PATCH /api/owner/reservations/{reservationId}`, 회원 식별은 `X-Member-Id`다. 본문은 `reservationStartAt`, `partySize` 중 바꿀 필드만 보낸다. 테이블은 서버가 정한다.
- 빈자리 부족은 `409`다. 성공 후 `RESERVATION_CHANGED` SSE를 받으면 예약 상세를 다시 조회한다. 같은 요청 재시도로 알림을 중복 발행하지 않는다.
- 프런트의 시작 전 제한과 이벤트 수신을 이 계약에 맞춰야 한다. 프런트 저장소는 수정하지 않았다.

## 검증

| 확인한 내용 | 실제 명령·환경 | 결과 |
| --- | --- | --- |
| 전체 테스트·패키징 | 격리된 PostgreSQL 16, DB `catchtable_feature37_test`, `DB_HOST=127.0.0.1 DB_PORT=54247 DB_NAME=catchtable_feature37_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew clean build --no-daemon` | 294개 테스트 / 37개 클래스, 실패·오류·건너뜀 0, bootJar 생성 성공 |

기존 매장 권한, 재시도 알림, 동시 배정, 실패 시 원상 보존과 신규 시작 후 변경·인원 변경·예약/선점으로 찬 목적 시간 거부를 포함한다. 관련 테스트: ReservationSettingsTest 42개. CI 결과는 PR 게시 후 별도로 확인한다.

## 남은 점

결제 금액과 환불 정책 스냅샷 보존의 실제 연결은 공통 결제 계약 후 진행한다. 예약 변경 후 환불 날짜 기준은 현재 예약 시각이며 최초 금액·정책 종류를 유지한다. #66의 이 후속 범위는 남아 있으므로 이번 PR로 이슈를 자동 종료하지 않는다. 프런트 실행·배포 검증은 수행하지 않았다.

## 2026-10-28 — 최초 금액·정책 보존 회귀 검증

[기능10 스냅샷 저장 경계](65-deposit-snapshot.md)의 `depositAmountSnapshot`·`refundPolicySnapshot`을 실제 DB 예약에 넣어 기존 `OwnerReservationService.updateReservation` 경로를 검증했다. 기존 변경 로직은 두 필드를 수정하지 않으므로 생산 코드를 추가하지 않았다.

- 시작 후 미입장 예약의 인원 변경·테이블 재배정 및 기존 이용 구간 유지, 미래 시각으로 변경 후에도 최초 10,001원·STRICT 정책이 유지된다.
- 목적 시간대가 확정 예약 또는 선점으로 찬 경우 원래 예약·슬롯·스냅샷을 함께 보존한다.
- HTTP 변경 재요청에서 스냅샷을 보존하고 기존 알림 한 번 발행 검증을 유지한다.
- 신규 전용 PostgreSQL16 컨테이너 `catchtable-phase2-astra-20261028`, `127.0.0.1:49498`, DB `catchtable_snapshot_independent_test`에서 `DB_HOST=127.0.0.1 DB_PORT=49498 DB_NAME=catchtable_snapshot_independent_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew test --tests 'com.catchtable.reservation.service.ReservationSettingsTest' --no-daemon`을 실행했다. 42개 모두 통과, 실패·오류·건너뜀0이다. `git diff --check`도 통과했다.

이 변경은 기능10 독립 커밋 `36d473ff4e8ad9c789314628379baf82c389d808`에 의존하는 테스트·기록만 포함한다. 공통 결제 코드나 실제 Toss 승인 흐름을 추가하지 않는다. 유료 예약 생성 호출부는 기능33 설정 계약과 기능10 연결이 남아 있으므로 #66 전체를 완료·종료하지 않는다. 기존 PR #120의 변경을 재구현하지 않았다.
