# 환불 멱등 응답 시각 표현 복구 — #205

2026-10-29. 기능60 [명세](https://app.notion.com/p/3d548d76fa3281369431cf4bd1c07a00), 공통 기능62 [명세](https://app.notion.com/p/3d548d76fa3281129329e520d6a5f762).

## 문제와 범위

`pr155-local-review`는 당시 로컬 검토·검증까지만 수행해 커밋/게시하지 않은 통합 검토 폴더였다. 최신 dev `dd9d96dd866d96db34be15ecd3618909e99b52a0`와 대조했다. 마이크로초 절삭은 공통 서비스의 기존 `now()`에 이미 반영돼 있어 엔티티의 중복 수정은 가져오지 않았다. 남은 문제는 첫 응답의 `+09:00`과 DB 재조회 응답의 `Z`가 같은 시점이지만 다른 JSON을 만드는 것이다.

같은 키 재요청의 첫 결과 유지 계약에 맞춰 `RefundResult.from`에서 완료 시각만 UTC로 표현한다. 금액·환불 상태·DB/멱등키·외부 호출/트랜잭션 경계는 유지하고 NOT_REQUIRED의 null도 유지한다. 나노초가 있는 고정 Clock, 실제 DB 완료/요청 시각, 첫/재요청 JSON 일치와 Toss 실행1회를 검증했다. 테스트 클래스 종료 시 context를 반환한다.

## 검증

Java21.0.12.1, 이번 작업 전용 PostgreSQL16, 기본 연결 한도100, 빈 최종 DB `audit_refund_final`. TOSS_SECRET_KEY는 빈 값, base URL은 loopback이며 fake PG만 사용했다.

- 수정 전 `./gradlew test --tests 'com.catchtable.payment.service.PaymentRefundServiceTest' --no-daemon --console=plain`: 12개 중1개 실패. JSON 시각 표현 차이를 재현했다.
- 수정 후 같은 검사: 12개 통과, 실패/오류/건너뜀0.
- 최종 `./gradlew clean build --no-daemon --console=plain`: 54 classes / 479 tests; failures/errors/skipped 0; bootJar success. 검증한 앱/테스트 입력은 로컬 manifest로 보존했고 이후 work-log만 추가했다.
- 최초 전체 빌드 두 개를 같은 컨테이너에서 병렬 실행해 연결 고갈11건이 발생했다. DB 한도를 올리지 않고 새 빈 DB에서 직렬로 전체 빌드를 실행했다. 실패 로그와 XML도 남겼다.
- 기존6개 dirty worktree의 HEAD·index/working patch·파일 SHA와 원본 archive를 보존했다. 새 마이그레이션과 원본 수정은 없다.

## 남은 범위

소스는 dev 대상 일반 PR·미머지다. 기능60 연동 문서는 별도 #208에서 게시한다. 실제 Toss·공유 DB 적용·프런트·배포는 검증하지 않았다. 기존 공유 V20 이력 결정은 이 수정과 무관하며 우회하지 않는다.
