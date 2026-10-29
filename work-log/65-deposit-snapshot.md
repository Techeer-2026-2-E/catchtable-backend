# 기능10 — 예약금 조건 스냅샷 저장 경계

- 작업일: 2026-10-28
- 이슈: [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65)
- 정본: [기능10](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b), [기능33](https://app.notion.com/p/3d548d76fa3281d48af9ea164f74c563)
- 기반: `dev@10b434ca2dc979cdfd90b40cd87ee7092e9ce290`에서 분리했다. 공통 Payment·Toss 클라이언트·V19 및 그 테스트는 포함하지 않는다.
- 적용 순서: [기능58 PR #144](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/144)의 V19가 먼저 적용된 후 이 V20을 적용해야 한다. 아직 V19가 없는 새 테스트 DB에서도 검증하지만, V20 적용 뒤 V19를 새로 끼우는 순서로 운영하지 않는다. 공유 V19 번호는 변경하지 않았다.

## 문제와 변경

예약에 최초 예약금 조건을 보관할 필드가 없어 이후 예약 변경·환불 예상 조회가 사용할 원본이 없다. `depositAmountSnapshot`과 `refundPolicySnapshot`을 추가하고, JPA에서 생성 이후 갱신하지 않는다. `V20`은 금액 음수·정책 누락·미지원 정책을 DB에서도 차단한다. 기존 행의 금액·정책은 역산하거나 채우지 않고 null로 유지한다.

- 기존 무료 `Reservation.confirmed`는 0원·정책 없음으로 저장한다. 무료 예약 API·확정 동작은 유지한다.
- 금액·정책을 받는 `Reservation.held` 생성 경계는 양수 금액과 정책을 함께 요구한다. 인원수와 곱하지 않으며 선점 기한은 동일하게 5분이다.
- 기존 선점 생성 경계는 원본 조건을 모르는 상태를 유지한다. 설정이 없는데 기본 정책이나 임시 금액을 부여하지 않는다.
- 상태 전이·행 잠금·시간 중복 제약·기존 응답 DTO는 변경하지 않는다. 스냅샷은 예약 조건이며 실제 결제 성공의 증거는 공통 Payment의 승인 정보다.

## 결정과 경계

2026-10-28 사용자는 매장 예약금 설정 컬럼·점주 설정 API를 기능33 담당자의 구현·계약에 맡기도록 결정했다. 따라서 금액·정책 입력을 받는 생성 경계는 준비했지만 `ReservationService.createHold`의 실제 호출 연결은 하지 않았다. 이 작업은 기능10 전체 완료가 아니다.

승인 요청 전 READY는 원래 5분 기한을 유지하고, 승인 진행·결과 불명은 확인 전 재고를 반환하지 않기로 결정했다. 공통 진행 표시·멱등성·결과 재확인 계약이 아직 없으므로 prepare/confirm·선점 만료/해제 연동은 구현하지 않았다. 기능58의 READY 만료 제외 문구는 승인 전 단순 이탈과 구분해 후속 조정해야 한다.

## 검증

새 전용 PostgreSQL16 컨테이너 `catchtable-phase2-astra-20261028`, `127.0.0.1:49498`을 사용했다. 기능58과 함께 검증한 통합 기반과 별개로, 독립 브랜치는 새 DB `catchtable_snapshot_independent_test`에서 검증한다.

```sh
DB_HOST=127.0.0.1 DB_PORT=49498 DB_NAME=catchtable_phase2_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew test \
  --tests 'com.catchtable.reservation.entity.ReservationDepositSnapshotTest' --no-daemon
```

통합 기반에서 신규 11개 테스트와 전체 408개 테스트가 통과했다. 유료 조건의 DB 저장·재조회·해제 후 보존, 무료 0원, 기존 선점의 미확인 값, 양수 금액·정책 필수, DB 제약을 검증했다. 이후 테스트 준비 데이터의 기능58 의존을 제거했다. 독립 브랜치의 전체 빌드 결과는 아래 후속 결과로 구분한다.

독립 브랜치에서 `DB_NAME=catchtable_snapshot_independent_test`와 위와 같은 나머지 연결 변수를 사용해 `./gradlew build --no-daemon`을 실행했다. 기능58 없이 42클래스 343개 테스트가 통과했고 실패·오류·건너뜀은 0이다. 빈 DB에 V1~V18·V20 적용과 bootJar 생성, `git diff --check`를 확인했다. 이는 V20 후 V19를 적용해도 된다는 검증이 아니며 실제 통합 순서는 V19 → V20이다.

## 남은 점

기능33 설정 원본과 생성 연결, prepare/confirm, 승인/만료 경합, 기능62 요청 멱등성 및 기능63 재확인은 남아 있다. 기능37의 실제 예약 변경 경로 보존과 기능61 HTTP 조회는 각각 후속 작업에서 검증한다. 실제 Toss 호출·프런트 화면·CI·배포는 이 로컬 검증에 포함하지 않는다. #65는 열린 상태로 유지한다.

## 2026-10-29 사용자 결정 후속 — 승인 시작 보호와 정상 확정

기준은 정상 merge한 `dev@7d320491a6ed9107ac3149ed8cfaa1bb1dad45bb`이며 기존 공개 #145 head `8cb8e971ae31776b5246a15a24177fad39230448`의 이력을 보존한다. 앞선 미구현·미결정 기록은 당시 상태다. 사용자 실제 답변 `1C 2A 3A 4B 5A` 중 5A에 따라 유효한 최초5분 안에 시작을 저장한 승인은 원래 만료시각 뒤 서버에서 확인한 `DONE`도 **같은 테이블·같은 시간**에 한 번 확정한다. 실패·READY·TIMEOUT은 확정하지 않는다.

### 변경과 트랜잭션

- `POST /api/user/payments/confirm`, `X-Member-Id`, JSON `{orderId,paymentKey,amount}`를 제공한다. 반환은 기존 `PaymentResponse`이며 `DONE` 성공200, 확정 거절은 기존 공통 계약의 `FAILED`200이다. 입력400·미식별401·남의 주문404·불일치/진행/결과 불명409를 구분한다.
- `ReservationPaymentService`가 예약→결제 잠금 안에서 소유권·주문·금액·양수 최초 스냅샷·원래 기한을 검사하고 시작 시각/paymentId/orderId/paymentKey를 저장·커밋한다. 이 최초 연결은 같은 키 재요청으로 바뀌지 않으며 다른 결제의 진행 키도 유니크 제약으로 차단한다.
- 바깥 트랜잭션은 거부한다. 공통 `PaymentApprovalService.approve`를 **트랜잭션·행 잠금 없이** 호출하고 별도 짧은 트랜잭션에서 현재 연결·원장 `DONE`을 다시 검사한다. 예약 확정·시각은 한 번만 바꾸며 기존 `AFTER_COMMIT` 리스너로 `RESERVATION_CONFIRMED`를 전송한다. 예약 확정 롤백은 이미 커밋된 결제 결과를 지우지 않는다.
- 만료 배치·엔티티 유효 상태·가용 수량 SQL·활성 예약/테이블 설정 충돌 조회·명시적 해제와 기존 배정 SQL/GiST가 동일한 진행 보호를 따른다. 진행/불명에는 최초 expiresAt을 연장하지 않고 remainingSeconds=0이어도 raw `PENDING_PAYMENT`와 자리를 보존한다. 확인된 FAILED/CANCELED만 시작 보호를 해제한 뒤 기존 `releaseHold`로 즉시 반환한다. 원래 기한 이후의 확인된 실패는 EXPIRED가 된다. SYSTEM 취소·환불은 만들지 않았다.
- 같은 최초 키 재요청만 공통 멱등 승인을 사용한다. [Toss 공식 멱등키 계약](https://docs.tosspayments.com/reference/using-api/authorization)과 기존 [기능141 기록](141-payment-idempotency.md)에 맞춰 최초 시작부터15일 이상인 READY/TIMEOUT에는 외부 재승인을 보내지 않고409로 보존한다. 이미 서버 원장에 같은 키 DONE이 있으면 새 외부 승인 없이 5A 확정이 가능하다. 새 조회·웹훅·스케줄 복구는 만들지 않았다.

### 스키마와 적용 차단

`V24__protect_reservation_payment_approval.sql`을 추가했다. 최신 dev는 V22이고 V23은 V20 후속 적용 전략 자리로 비워 두었다. 기존 V20 파일의 SHA-256 `5a2d0f56b27320836841e258607edc86c239218cbc8eae6a6db294880eb978f2`를 유지하며 rename·repair·outOfOrder·ignore는 사용하지 않는다.

과거 **raw PENDING_PAYMENT** + TIMEOUT/DONE/PARTIAL_REFUNDED/REFUNDED 원장은 `approval_quarantined`로만 격리한다. 기존 행의 시작 시각·요청 키를 추정하지 않고 원장·예약 상태·시각·슬롯을 그대로 둔다. 준비만 된 READY는 원래5분을 유지하며 EXPIRED/CANCELLED는 되살리지 않는다. 격리 행은 모든 자리 보호 경로와 confirm409를 적용하고 기능142 확인 대상으로 남긴다.

공유·영속 DB의 V20 적용 여부가 여전히 미확인이므로 **V22(이미 적용, V20 미적용) → 이 후보 upgrade는 차단 상태**다. 빈 DB 통과와 V20 포함 기존 스키마에서 V24 적용 검증으로 이 차단이 해결되었다고 하지 않는다. 기능 PR은 Draft·미머지 검토용이다. CI base 허용 목록의 공통 선행 변경은 별도 [기술 PR #164](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/164)이며 기능 변경과 구분한다.

### 이번 검증

기존 DB/컨테이너는 시작하거나 변경하지 않았다. 새 disposable PostgreSQL16 `phase2-approved-20261029-pg16`, `127.0.0.1:54937`, `phase2_snapshot`에서 다음을 실행했다.

```sh
DB_HOST=127.0.0.1 DB_PORT=54937 DB_NAME=phase2_snapshot \
DB_USERNAME=phase2_test DB_PASSWORD=phase2_test ./gradlew test \
  --tests 'com.catchtable.reservation.ReservationApprovalMigrationTest' \
  --tests 'com.catchtable.reservation.service.ReservationPaymentServiceTest' \
  --tests 'com.catchtable.reservation.entity.ReservationDepositSnapshotTest' --no-daemon
```

44개(승인32·스냅샷11·마이그레이션1), 실패/오류/건너뜀0이다. HTTP 성공·실패·인증/소유권·입력/금액 검증, 동일/경쟁 키, 동시 확정 1회·원장 1회·알림 1회, PG 중 행 잠금 없음, 최초 기한 뒤 같은 자리 확정, READY/TIMEOUT·예외 시 모든 자리 보호, 확정 롤백의 결제 원장·알림 보존, 설정/생성/변경 충돌과15일 경계를 검증했다. V24의10개 기존 행과 결제/상태이력/환불 JSON 전후 비교로 격리 대상4개만 표식이 생기고 기존 데이터·GiST 제약이 보존됨을 확인했다.

전체 로그·명령·JUnit XML은 로컬 검토 자료 `approved-followup/approval-migration-targeted`에 보관한다. 확장 테스트의 최초 컴파일 실패(AssertJ generic 추론)는 수정했고 실패 로그도 `approval-compile-attempt`에 남겼다. 합본 전체 빌드·각 최종 head의 실제 CI는 별도 후속 결과로 구분한다.

### 남은 연결

기능33의 예약금 설정 원본·점주 계약이 없어 prepare API와 유료 생성 연결을 만들지 않았다. 올바른 준비 원장이 저장된 경우만 검증했으며 유료 생성 전체 E2E는 미완료다. 2A는 결제 환불과 예약 취소를 분리하며 고객 취소는 남은 **정책상** 환불액이 기준이고 임의 전액 환불을 허용하지 않는다. 3A의 양수 취소 환불에만 필요한 client Idempotency-Key는 미래 계약이다. 4B에 따라 최초 요청 시각/한도 저장·진행 중 변경 동결 계약 전 양수 환불 endpoint와 PG 기반 예약 취소는 보류하며 기존 양수 환불 차단을 유지한다. 기능142 복구·실제 Toss·프런트/JWT·배포는 이번 구현·검증 범위에 없다.

합본 첫 실행에서는539개 중 기존 WaitingQueueNotifierTest의7개가 새 컨텍스트 시작 시 PostgreSQL 기본100연결 한도(`too many clients already`)로 실패했다. 새 Clock/Toss mock 승인 테스트의 독립 Spring 컨텍스트를 `DirtiesContext(AFTER_CLASS)`로 테스트 종료 시 반환하도록 보완했다. 운영 설정·DB 한도·기존 테스트는 바꾸지 않으며 테스트를 제외하지 않는다. 실패 전체 로그/XML은 `approved-followup/aggregate-first-attempt`에 보존하고 최종 clean build 결과를 별도로 기록한다.


## 2026-10-29 후속 — 기능63 정산 경로의 예약 경계

[#179](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/179)의 `0c376957f6ad6d14166113dcdd91b6cc3b0fb774`와 기존 #145를 정상 merge한 별도 기능10 후속 후보다. #145/#147/#146/#150에는 이 후속 소스를 섞지 않는다. [기능10 명세](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b)의 동일 최초 요청·스냅샷 검증과 종료 예약 보존을 재확인 진입점에도 적용한다.

기존 `confirm`은 결제 회원·주문·키·금액과 최초 예약 스냅샷을 검사하지만 새 `settle`은 생략했다. 회귀 테스트에서 다른 key/order의 DONE은 기존 보호를 지웠고, amount/member가 다른 DONE은 예약을 확정했다. 같은 승인으로 이미 확정한 뒤 CANCELLED/COMPLETED/NO_SHOW가 된 예약과 미확정 terminal hold도 재확인 시 409를 반복했다. 기존 테스트에 추가한 14건 중 9건이 수정 전에 실패했다.

예약 서비스에서 기존 `validatePayment`를 재사용하고 DONE 확정을 PENDING_PAYMENT/CONFIRMED에 한정했다. 불일치는 409 PAYMENT_MISMATCH와 기존 자리·연결을 보존한다. 이미 확정한 종료 예약은 현재 상태·확정시각·이력·알림을 유지한다. 확정된 적 없는 CANCELLED/EXPIRED는 되살리지 않고 #179의 기존 LATE_SUCCESS/refundRequired 경로로 보낸다. 공통 승인·재확인·환불 서비스와 결제 엔티티·마이그레이션은 변경하지 않았다.

새 disposable PostgreSQL16 `phase2-reconcile-20261029-pg16`, localhost:54941, DB `phase2_targeted`, 기본 max_connections=100에서 `./gradlew test --tests '*ReservationPaymentServiceTest' --tests '*PaymentReconcile*Test' --no-daemon`을 실행했다. 예약 승인46개와 재확인26개, 총72개가 실패·오류·건너뜀0으로 통과했다. 기존 Toss mock/fake와 Clock을 재사용했고 빈 Toss 키·loopback base URL로 실제 결제 스케줄러/외부 호출을 막았다. 추가 검증은 조회 중 DB 행 잠금 부재, 원래 기한 후 동일 슬롯 보호, 실패 경계의 반환/재배정/재요청, 다른 결과가 불명일 때 과거 전액 환불의 격리 유지, sync/detail의 평탄한 PaymentResponse+refunds JSON과 refundRequired를 포함한다.

전체 합본 검증과 V20 없는 populated V22의 업그레이드 차단은 별도 증거다. 이 후속으로 유료 생성/prepare, 양수 환불 실행(4B), 공유 V20 rollout, 실제 Toss·프런트/JWT·배포를 완료한 것으로 보고하지 않는다.
