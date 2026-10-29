# 기능60 — 환불 확인 전 예약 취소 방지

- 작업일: 2026-10-28
- 이슈: [#68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)
- 명세: [기능60](https://app.notion.com/p/3d548d76fa3281369431cf4bd1c07a00)
- 기반: 기능61 [PR #146](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/146)의 `9af6392e16b8b3e2e957cafb78938be00234b88d`. 기능58 [PR #144](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/144)·스냅샷 [PR #145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145)에 의존한다.

## 문제와 승인된 기준

기존 고객·점주 예약 취소는 결제 상태를 확인하지 않아 유료 예약도 즉시 취소·반환할 수 있었다. 2026-10-28 사용자는 외부 환불 성공 확인 또는 환불액 0원 결정 이후에 예약 취소·자리 반환을 반영하고 실패·결과 불명에는 기존 예약·자리를 유지하도록 결정했다.

공통 요청 멱등성·환불 진행/결과 불명 계약이 없는 현재는 양수 환불을 안전하게 실행할 수 없다. 이 변경은 기존 취소 API가 그 경계를 우회하지 못하게 하고 이미 확인 가능한 무료·0원·필요 환불 완료만 처리한다. 새로운 외부 환불 성공 응답이나 가짜 결제는 만들지 않는다.

## 실제 변경

- `ReservationRefundService`로 기능61의 원본 검증·계산을 공유한다. 조회는 기존 읽기 전용 트랜잭션을 유지한다.
- 고객·점주 취소는 기존 예약 행 잠금 후 해당 예약의 Payment 행을 id 순으로 잠근다. 현재 PaymentService는 Payment만 잠그므로 기존 공통 결제 잠금 순서를 거꾸로 만들지 않는다. 향후 승인/환불 호출부도 예약 → Payment 순서를 맞춰야 한다.
- 기존 도메인 취소 검증 후 환불 조건을 확인한다. 양수 추가 환불은 `409 REFUND_NOT_COMPLETED`, 승인 원장/금액/정책 미확인 또는 READY/TIMEOUT은 `409 REFUND_DATA_NOT_READY`다. 예외 시 예약 상태·취소시각·자리 반환이 함께 롤백된다.
- 무료, 정책상 추가액0, 정책 한도까지 기환불 확인된 경우에는 기존 취소를 커밋한다. 점주 취소는 날짜·정책에 상관없이 최초 금액 전체가 한도다. 이미 취소된 예약의 재요청은 최초 결과를 유지한다.
- 고객 알림은 검증 뒤 발행하고 기존 AFTER_COMMIT 전달을 유지한다. 롤백은 알림을 보내지 않고 성공 재요청은 중복 발행하지 않는다.
- 점주 취소의 시각도 주입된 Clock을 사용해 환불 계산 기준과 취소 기록을 일치시켰다.
- 0원·이미 환불 완료를 포함해 이 경계는 외부 Toss 호출이나 Payment 원장 갱신을 하지 않는다. `POST /api/user/payments/{paymentId}/cancel`은 여전히 미구현이다.

## 검증

새 전용 PostgreSQL16 컨테이너 `catchtable-phase2-astra-20261028`, `127.0.0.1:49498`, DB `catchtable_phase2_test`에서 실행했다. `DB_HOST=127.0.0.1 DB_PORT=49498 DB_NAME=catchtable_phase2_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew build --no-daemon`이 성공했다. JUnit XML 집계는 48개 클래스·462개 테스트, 실패·오류·건너뜀 0개다. 신규 취소 경계 21개와 기존 환불 미리보기·고객/점주·동시성 회귀를 포함한다. 전체 로그는 작업 산출물 `feature60-build-462-tests.log`로 보존했다.

신규 테스트는 고객 정책별 양수·0원, 기존 부분/전액 환불, 날짜 이후에도 점주 전액 기준, 기존·신규 무료, 승인 결과 불명·원본 누락, HTTP 오류, 실제 커밋/롤백 후 슬롯 상태, 재요청의 SSE1회, 외부 호출 없음과 Payment 잠금 대기 후 확정 환불 읽기를 검증한다.

## 남은 범위

이 변경은 실제 전액·부분 환불 실행 완료가 아니다. 양수 환불은 거절되므로 기능62의 요청 키·기준시각·요청 금액 보존과 기능63의 결과 재확인 계약 후 외부 취소 실행을 연결해야 한다. 현재 공통 Payment의 TIMEOUT은 승인 전 결과 불명을 표현하며, 승인 후 환불 요청 진행/결과 불명 자체를 구분할 계약은 없다. 이를 임시 새 상태나 요청 테이블로 대신 구현하지 않았다.

향후 외부 환불 경로를 활성화하기 전에 그 진행·결과 불명 표식을 이 경계와 함께 확인해야 한다. 이 코드만으로 미래의 미확인 환불 요청까지 구분한다고 주장하지 않는다. 이미 과거 코드로 취소된 예약을 복구하거나 누락 환불을 실행하는 보정도 포함하지 않는다. #68은 열린 상태로 유지하며 실제 Toss·프런트·CI·배포는 미확인이다.


## 2026-10-29 후속 — 공통 환불 진행 기록 보존

- 기준: 최신 `dev@555e0aabd243b52e5007e55460d2aae398952f27`과 공통 기능62를 별도 로컬 브랜치에 merge했다. 기존 공개 PR #150 head/base는 그대로 두었다.
- 공통 `PaymentRefund`의 `REQUESTED`·`UNKNOWN`이 있으면 기존 `409 REFUND_DATA_NOT_READY`로 예상액·고객 취소·점주 취소를 차단한다. 정책상 추가 환불액이0이어도 진행 중 환불을 완료로 간주하지 않는다. 예약·자리·취소 알림은 기존 트랜잭션에서 함께 보존된다.
- 새 PostgreSQL16 컨테이너 `phase2-sol-max-20261029-pg16`, 전용 DB `phase2_refund`, `DB_PORT=54936`에서 `./gradlew test --tests com.catchtable.reservation.service.ReservationCancellationRefundTest --tests com.catchtable.reservation.service.ReservationRefundPreviewTest`: 53개 통과, 실패·건너뜀0. 전체 로그·명령·XML은 인계 산출물 `refund-inflight-targeted/`에 보관했다.
- 양수 환불 실행은 여전히 `REFUND_NOT_COMPLETED`로 차단한다. 결제 환불 API와 예약 취소 결합 방식·멱등키·최초 요청시각 및 변경 동결은 사용자 결정 대기라 새 HTTP endpoint를 제공하지 않았다. 공통 서비스 자체의 완료를 기능60 전체 완료로 쓰지 않는다.
- V20 이력·upgrade, 실제 Toss·프런트 연동·배포·새 CI 실행은 미확인이다. 기능63을 대신 구현하지 않았다.

## 2026-10-29 실제 결정 후속 — 양수 실행 보류 유지

사용자 실제 답변2A는 결제 환불 endpoint가 돈만 환불하고 예약을 유지하며, 예약 취소는 남은 **정책상** 환불액을 먼저 처리한 뒤 취소한다는 계약이다. 고객에게 임의로 최초 전액을 돌려주는 계약이 아니다. 3A는 양수 환불을 실행하는 예약 취소에만 client Idempotency-Key가 필요한 미래 계약이며 기존 무료 취소 요청은 유지한다. 사용자4B는 최초 요청시각/한도 저장·진행 중 변경 동결 합의 전 실행을 보류한다는 결정이다. 따라서 양수 환불 endpoint·PG 기반 예약 취소·새 환불 fence를 구현하지 않고 `REFUND_NOT_COMPLETED`와 REQUESTED/UNKNOWN의 `REFUND_DATA_NOT_READY` 보호를 유지했다.

기능10의 정상 confirm/영속 승인 보호와 V24, dev `7d320491a6ed9107ac3149ed8cfaa1bb1dad45bb`, CI 선행 [#164](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/164)를 preview 부모에서 정상 merge했다. 기존 공개 #150 head `a000485fee9c3970fb3fb55b57ddd07b26c98689`와 독립 후속 `665c120`의 보호를 보존한다. 5A의 late DONE 확정은 승인 경계이며 환불 실행 허용으로 확대하지 않는다.

새 disposable PostgreSQL16 `phase2-approved-20261029-pg16`, `127.0.0.1:54937/phase2_refund_approved`에서 아래87개가 실패/오류/건너뜀0으로 통과했다. 로그·XML은 로컬 `approved-followup/refund-targeted`에 보존한다.

```sh
DB_HOST=127.0.0.1 DB_PORT=54937 DB_NAME=phase2_refund_approved DB_USERNAME=phase2_test DB_PASSWORD=phase2_test ./gradlew test --tests 'com.catchtable.reservation.service.ReservationCancellationRefundTest' --tests 'com.catchtable.reservation.service.ReservationRefundPreviewTest' --tests 'com.catchtable.reservation.service.ReservationPaymentServiceTest' --no-daemon
```

고객/점주 취소·예상액·정상 승인 경계를 함께 확인했다. V20 공유 이력·upgrade 차단은 [기능10 기록](65-deposit-snapshot.md)을 따른다. 실제 source CI·Toss·프런트·배포는 이 검사에 포함하지 않는다. 합본 전체 빌드는 별도 결과다.


## 2026-10-29 후속 — 재확인 완료와 예약 취소의 통합 경계

기존 #150을 변경하지 않는 별도 테스트 후속 후보다. 선행 범위는 #145/#146/#150과 공통 [#179](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/179), 예약 정산 경계 [#180](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/180)다. 기능37 코드를 섞지 않으며 새 환불 실행·취소 endpoint는 추가하지 않는다. [기능60 명세](https://app.notion.com/p/3d548d76fa3281369431cf4bd1c07a00)와 [기능61 명세](https://app.notion.com/p/3d548d76fa3281699f09e28ed2198911)의 기존 보호를 검증한다.

기존 `ReservationCancellationRefundTest`와 공통 fixture를 재사용해 5건을 추가했다. 고객/점주와 REQUESTED/UNKNOWN의 네 조합에서 Toss 조회가 진행되는 동안 예상액과 취소가 409 REFUND_DATA_NOT_READY이고 예약·자리·SSE는 유지된다. 조회 중 바깥 트랜잭션/예약·결제 행 잠금이 없음을 별도 연결로 확인한다. 검증된 기존 전액 환불 결과가 커밋된 뒤에도 돈 환불 자체는 예약을 유지하며, 그 뒤 취소와 재요청만 자리와 알림을 한 번 변경한다. 최초 환불 키·시각·요청액은 보존하고 Toss cancel은 호출하지 않는다. 엄격 정책 당일 고객의 추가 환불0원 취소 뒤 DONE을 재확인해도 취소가 유지되고 refundRequired가 생기지 않는 실제 취소 경로도 확인했다.

승인 연결 FK가 있는 fixture 정리는 기존 `PaymentTestData.deleteStore`를 재사용한다. normal merge 때 중복된 `List` import 한 줄만 제거했으며 공통 결제/환불 로직·마이그레이션은 변경하지 않았다.

새 disposable PostgreSQL16 localhost:54941, DB `phase2_targeted`, 기본 max_connections=100에서 `./gradlew test --tests '*ReservationCancellationRefundTest' --tests '*ReservationRefundPreviewTest' --no-daemon`을 실행해 두 클래스60개가 실패·오류·건너뜀0으로 통과했다. 전체 합본·source CI·기존 DB upgrade는 별도 검증이다. 실제 Toss·프런트/JWT·배포, 사용자4B가 보류한 새 양수 환불 실행은 포함하지 않는다.
