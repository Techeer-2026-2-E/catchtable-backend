# CI — 테스트 Clock spy의 반환 타입 오류

## 문제와 근거

[PR #245](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/245) 병합 후 `dev@2b3eebd`의 [CI 실행 41795121400](https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/41795121400)은 861개 중 `PaymentRefundServiceTest.sameKeyReturnsFirstResult` 1개가 실패했다. `Clock$SystemClock.getZone()`에서 `Instant`를 `ZoneId`로 변환하는 `ClassCastException`이며, 기존 [#239 병합 CI 41659890485](https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/41659890485)에도 같은 테스트·예외가 있다. 실패한 환불 테스트와 운영 서비스는 #245에서 바뀌지 않았다.

테스트는 스케줄러도 읽는 Spring `Clock` 빈을 spy로 감싸고 `doReturn(instant).when(clock).instant()`로 시간을 변경했다. 저장소 밖의 로컬 실험에서 스텁 설정 도중 다른 스레드의 `getZone()` 호출을 끼워 넣자 `WrongTypeOfReturnValue: Instant cannot be returned by getZone()`이 발생했다. CI의 정확한 스레드 교차 순서는 로그만으로 확정하지 않았지만, 공유 시계의 스텁 등록과 읽기가 간섭하는 위험을 확인했다.

## 변경

`PaymentRefundServiceTest`, `ReservationRefundPreviewTest`, `StoreSearchControllerTest`의 시계 spy를 제거했다. 기존 `ReconcileTestClock`의 `@Primary` 설정·고정 `Asia/Seoul` 시간대·`AtomicReference` 기반 시각 변경을 재사용한다. 매 테스트 전 시각을 초기화하며, 환불 재요청·마이크로초 저장 정밀도·서울 자정 전후 정책·매장 조회의 기존 검증을 유지한다. 테스트 수와 단언을 줄이거나 스케줄러를 비활성화하지 않았다.

## 검증 — 2026-11-04

Java 21.0.12.1, 새 일회용 PostgreSQL16, `127.0.0.1:65157`, DB `ct_ci_clock_test`에서 빈 Toss 키와 `TOSS_BASE_URL=http://127.0.0.1:9`로 `./gradlew clean build --no-daemon`을 실행했다. **81클래스 861개, 실패·오류·건너뜀0**, bootJar 성공이다. 기존 마이그레이션 30개의 해시가 유지됐고, bootJar SHA-256은 #245 검증 산출물과 같은 `964f239df9582d3d9f05b635c55712752ba1b3b313314b1dfc3631888e9e1842`이다. 운영 코드·설정·의존성은 변경하지 않았다.

실패 CI 원본 로그 두 건, 로컬 동시 접근 실험과 전체 빌드 로그·JUnit XML·명령·DB 대상·JAR 해시를 별도 로컬 산출물에 보존했다. 검증용 DB는 tmpfs·종료 시 자동 삭제로 만들었으며 검증 후 제거했다. GitHub CI와 리뷰·병합 상태는 후속 PR에서 확인한다. 실제 Toss 결제·프런트 연동·배포 검증은 이번 범위에 포함하지 않는다.
