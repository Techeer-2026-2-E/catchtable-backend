# 기능10·60 — 실제 매장 예약금 설정부터 결제·환불까지 검증

## 배경과 범위

[기능33 PR #242](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/242)가 `dev`의 `e7c81ccb12c9bc141b177439d4c99d3dbdc131c1`에 머지되어 `StoreDepositTermsReader`와 매장 예약금 설정 API를 제공한다. 기존 매장 설정 검증은 선점 스냅샷까지였고, 결제·환불 통합 테스트는 테스트 전용 설정 공급자를 사용했다.

[기능10](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b), [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [#68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)의 후속으로 점주 설정 API → 운영 리더 → 고객 선점 → prepare → 승인 → 환불 경계를 검증했다. 외부 PG 응답만 기존 `FakeTossPaymentsClient`를 사용한다. 새 업무 규칙이나 운영 코드·스키마 변경은 없다.

## 변경

`StoreDepositPaymentFlowTest`는 운영 `StoreDepositTermsReader`가 주입됐는지 확인하고 다음 네 경우를 실제 PostgreSQL과 공개 컨트롤러로 실행한다.

- 매장 설정 10,001원·STANDARD로 4인 선점을 만든 뒤 매장 설정을 바꿔도 최초 금액·정책·5분 기한과 READY 주문을 보존한다. 중복 승인 후 외부 승인 호출은 한 번이며, 서울 날짜 기준 50% 한도에서 부분 환불 1,000원과 고객 취소 4,000원을 정산하고 같은 취소 요청을 재사용한다.
- 예약금 설정을 끈 뒤에도 기존 STRICT 예약의 점주 취소는 최초 10,001원을 한 번 전액 환불한다. 이후 새 무료 예약은 기존 즉시확정 API로 생성된다.
- 승인 거절이면 자리가 반환되어 같은 시간에 새 선점·결제 준비가 가능하다.
- 승인 결과 불명이면 최초 5분이 지나도 다른 선점을 차단한다. 동일 결제의 DONE 재확인으로 기존 예약을 확정한다.

기존 회원 fixture와 `ClockFixture`, `PaymentTestData`, fake PG를 재사용한다. 생성한 매장·회원·결제·환불만 정리한다. 실제 매장 설정 공급자를 fake로 대체하지 않는다.

## 로컬 검증 — 2026-11-04

Java21, 새 일회용 PostgreSQL16, loopback `127.0.0.1:49788`, 빈 DB `ct_deposit_test`, `TOSS_SECRET_KEY=` 및 `TOSS_BASE_URL=http://127.0.0.1:9`에서 실행했다. DB 데이터는 tmpfs이고 컨테이너는 종료 시 자동 삭제하도록 만들었다. 기존 컨테이너·개발 DB·공유 DB는 테스트 대상으로 쓰지 않았다.

| 명령 | 결과 |
| --- | --- |
| `./gradlew test --tests com.catchtable.reservation.service.StoreDepositPaymentFlowTest --no-daemon` | 1클래스 4개 통과 |
| `./gradlew clean build --no-daemon` | 81클래스 859개 통과, 실패·오류·건너뜀 0, bootJar 생성 |

전체 실행 전후 기존 마이그레이션 30개 SHA-256이 동일하다. 전체 로그·명령·JUnit XML과 JAR 해시를 별도 로컬 검증 산출물에 보존했다. 최초 DB 초기화 중 소켓 준비 확인이 임시 서버를 통과해 `createdb`가 한 번 실패했고, 최종 TCP 리스너와 빈 DB를 확인한 뒤 위 검증을 실행했다. 이 준비 실패를 테스트 통과 수에 포함하지 않는다.

## 확인 범위

이 결과는 실제 매장 설정을 읽는 백엔드 통합 검증이다. Toss 테스트 서버 승인·환불, 제품 프런트 연결, 공유 DB의 V31 적용과 배포는 별도 확인이다. 사용자 선택에 따라 실제 PG 후속 검증은 Toss 테스트 모드로 진행하며, 이 fake PG 결과를 실제 PG 결과로 사용하지 않는다. 상위 #65·#68의 완료 여부는 해당 후속 결과까지 보고 판단한다.
