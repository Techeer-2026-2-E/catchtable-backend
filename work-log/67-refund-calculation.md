# 기능61 — 취소 시점별 환불금 계산

- 작업일: 2026-10-27
- 관련 이슈: [#67 환불금 계산](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/67)
- 정본: [기능61 명세](https://app.notion.com/3d548d76fa3281699f09e28ed2198911)
- 기준: `dev@61aaf744eab5fbfb8024e1c2fe83b915124c4f41`, Java 21, PostgreSQL 16.

## 배경과 목표

공통 결제 구현과 독립적으로, 명세의 유연·일반·엄격 정책에 따른 환불 비율과 추가 환불액을 재현 가능하게 계산한다. 결제·환불 실행이나 조회 API를 구현한 것으로 취급하지 않는다.

## 판단과 근거

정책별 비율은 Notion 기능61을 따른다. 다음 미정 항목은 2026-10-27 사용자 답변으로 확정했다.

- 원 미만은 반올림 대신 버린다. 1,001원의 50%는 500원이다.
- 총 환불 가능액은 원결제액 기준으로 계산한 뒤 기환불액을 뺀다. 원결제 10,000원, 비율 50%, 기환불 1,000원이면 추가 환불은 4,000원이다. 잔여 결제액에 비율을 다시 곱하지 않는다.
- 점주 취소는 시점과 관계없이 원결제액의 100%를 총 환불 한도로 사용한다. 이미 환불한 금액은 다시 지급하지 않는다.
- 예약 변경 후에는 변경된 방문일을 기준으로 비율을 정한다. 금액과 정책 종류는 최초 예약 스냅샷을 유지한다.
- 날짜 경계는 기존 매장 시간대 `Asia/Seoul`을 따른다. 경과한 24시간 수가 아니라 현지 날짜 차이를 사용한다.

사용자는 윤서 담당인 기능58·62·63의 공통 결제 상태·멱등성·결과 재확인은 계약 후 연결하도록 정했다. 별도 결제 모델이나 임의의 시스템 취소 환불 규칙은 만들지 않는다. 외부 Notion 정본에는 이번 결정을 아직 반영하지 않았다.

## 실제 변경과 연동 입력

- `RefundPolicy`, `RefundCalculation`, `ReservationRefundCalculator`와 테스트를 추가한다. 새 의존성·스키마·HTTP API는 추가하지 않는다.
- 계산 입력은 최초 결제 금액·정책 스냅샷, 공통 결제 원본의 누적 환불액, 현재 예약 시작 시각, 취소 시각, 취소 주체, 노쇼 여부다.
- 반환값은 비율·추가 환불액·계산 근거다. 원 미만 버림 후 기환불액을 차감하고 추가 환불액은 0 이상이다. 음수·초과 누적 환불액을 거부하며 큰 금액 계산의 오버플로를 방지한다.
- 고객 노쇼는 0%다. `SYSTEM` 취소를 고객 정책으로 추정하지 않고 지원하지 않는 입력으로 거부한다. 시스템 보정은 공통 결제 계약 후 연결한다.
- 프런트는 서버가 반환하는 환불 예상액을 사용해야 한다. `GET /api/user/reservations/{reservationId}/refund-preview`는 아직 구현하지 않았다.

## 검증

| 확인한 내용 | 실제 명령·환경 | 결과 |
| --- | --- | --- |
| 전체 테스트·패키징 | 격리된 PostgreSQL 16, DB `catchtable_feature61_test`, `DB_HOST=127.0.0.1 DB_PORT=54247 DB_NAME=catchtable_feature61_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew clean build --no-daemon` | 316개 테스트 / 38개 클래스, 실패·오류·건너뜀 0, bootJar 생성 성공 |

정책별 비율, 한국 자정 경계, 원 미만 버림, 기환불 차감·0원 하한, 점주 전액·고객 노쇼, 변경 후 방문일, 큰 금액과 잘못된 누적액·시스템 취소 거부를 포함한다. 관련 테스트: ReservationRefundCalculatorTest 28개, ReservationSettingsTest 36개. CI 결과는 PR 게시 후 별도로 확인한다.

## 남은 점

정책 스냅샷과 누적 환불액의 원본, 본인 예약 권한 검증을 포함한 예상액 API, [기능60 환불 실행](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)은 공통 결제 계약 후 연결한다. #67 전체는 아직 완료가 아니므로 이번 PR로 자동 종료하지 않는다. Toss·프런트 실제 화면·배포 검증은 수행하지 않았다.

## 2026-10-28 — 환불 예상액 조회 연결

사용자는 본인 예약 중 시작 전·미입장·`CONFIRMED` 예약만 조회하도록 결정했다. 취소·완료·노쇼·선점 및 시작 시각과 같거나 이후인 예약은 `409 RESERVATION_NOT_CANCELLABLE`이다. 이전 Notion API의 취소된 건 조회 제안은 이번 범위에 적용하지 않는다. 기존 계산기의 정책·원 미만 버림·기환불액 차감은 변경하지 않았다.

### 실제 변경

- `GET /api/user/reservations/{reservationId}/refund-preview`, 기존 `@LoginMember`의 `X-Member-Id` 계약을 사용한다. 없는 예약과 다른 고객의 예약은 모두 `404 RESERVATION_NOT_FOUND`다.
- 예약의 최초 `depositAmountSnapshot`·`refundPolicySnapshot`, 현재 예약 시작 시각, 서버 Clock, 공통 Payment의 승인 원장과 누적 환불액으로 계산한다. 읽기 전용 `REPEATABLE_READ` 트랜잭션으로 예약과 결제를 같은 DB 시점에서 읽는다.
- 기존 무료 즉시 확정은 `holdExpiresAt == null`이고 결제 행이 없는 경로였다. 이런 기존 무료 예약과 명시적 0원 스냅샷은 금액 0·정책 없음·`paymentId=null`을 반환한다. 선점 출신이거나 결제 행이 있는 원본 누락 건은 무료로 간주하지 않는다.
- 유료 스냅샷 누락, 승인 원장 없음·여러 건, 진행/결과 불명 결제, 금액·회원 불일치는 `409 REFUND_DATA_NOT_READY`다. 현재 매장 정책으로 채우지 않는다.
- 응답은 `reservationId`, `paymentId`, `currency`, `paidAmount`, `alreadyRefundedAmount`, `policyType`, `reservationStartAt`, `calculatedAt`, `refundRate`, `refundableAmount`, `nonRefundableAmount`, `policyDescription`이다. `refundableAmount`는 이번 추가 환불 가능액, `nonRefundableAmount`는 미환불 잔액 중 환불 불가액이다. 무료 응답의 비율은 0, 정책은 null이다.
- 조회는 예약 취소·자리 반환·Payment 상태 변경·외부 Toss 요청·알림을 실행하지 않는다. 실제 취소 시점에는 다시 계산해야 한다.

### 기반과 소유권

공통 결제 [PR #144](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/144)의 `c1534ed65145a4af2b5e3b813a65649f3705d93f`를 읽어 사용했다. 기능58 엔티티·상태·클라이언트·실패/결과 불명 처리를 재구현하지 않았으며, 기존 PaymentRepository에 예약별 조회 메서드만 추가했다. 기능10 스냅샷과 V19 → V20 적용 순서가 선행한다. 기능62 요청 멱등성·기능63 재확인과 기능60 실제 환불은 포함하지 않는다.

### 검증

새 전용 PostgreSQL16 컨테이너 `catchtable-phase2-astra-20261028`, `127.0.0.1:49498`, DB `catchtable_phase2_test`에서 `DB_HOST=127.0.0.1 DB_PORT=49498 DB_NAME=catchtable_phase2_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew build --no-daemon`을 실행했다. 47클래스 441개 테스트, 실패·오류·건너뜀0이며 bootJar를 생성했다. 신규 `ReservationRefundPreviewTest` 30개는 HTTP 소유권·헤더, 시작 시각·상태, 한국 자정, 원 미만 버림·누적액, 변경 후 방문일, 기존 무료·신규 무료·원본 누락 및 반복 조회의 무변경을 검증했다. `git diff --check`를 통과했다.

유료 생성 흐름은 기능33 설정과 기능10 호출부 연결 대기이므로 이 검증의 유료 데이터는 테스트 fixture다. 실제 결제 승인으로 만든 예약에서 프런트까지 연동한 결과가 아니다. CI·실제 Toss·프런트 실행·배포는 미확인이다. 기능61 HTTP 조회 자체는 구현했지만 선행 기능 미병합과 유료 원본 생성 연결이 남아 #67을 닫지 않는다.
