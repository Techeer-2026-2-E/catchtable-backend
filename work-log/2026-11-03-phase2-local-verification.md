# Phase 2 로컬 검증과 현재 연동 계약 정리

- 작업일: 2026-11-03
- 관련: [예약금 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [만료시간 #62](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/62), [반환 #63](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/63), [재예약 #64](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/64), [환불 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68), [점검 도구 PR #226](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/226)
- 기준: 백엔드 `dev@5db45f057fb593a71e6d28b787ba65ceff1f7375`, 프런트 읽기 대조 `dev@015f333aa2b2d4230560d69013136190b5ea2c87`.

## 범위와 변경

사용자가 후속 선택을 위임하고 로컬 DB만 사용하도록 정했다. 기능33의 매장 설정 저장·API·실제 공급자는 기존 담당을 유지한다. 이번에는 기존 백엔드 소비 경계·실행 계약을 검증하고, 실제 Toss 검증의 기존 보류와 프런트 코드 변경 제외를 유지했다.

PR #226의 두 파일·정확한 head `8aee0356705b6d097cd522f99b23f85e2d2dfcee`의 CI SUCCESS와 리뷰 승인을 재확인했다. 연결 도구의 머지는 integration 권한 403으로 거절돼 로컬 GitHub CLI에서 같은 head를 지정해 merge했다. `gh pr merge 226 --repo Techeer-2026-2-E/catchtable-backend --merge --match-head-commit 8aee0356705b6d097cd522f99b23f85e2d2dfcee` 후 API로 merge SHA `5db45f0`와 #224 종료를 확인했다. 브랜치를 삭제하지 않았다.

기존 프런트 연동 문서의 날짜별 미구현·후보 기록은 보존하고, [기능10의 현재 계약](../docs/frontend/feature-10-deposit-payment.md#2026-11-03-현재-계약)과 [기능60의 현재 계약](../docs/frontend/feature-60-refund-execution.md#2026-11-03-현재-계약)을 앞에 추가했다. 선점·타이머·반환·재예약 문서는 같은 현행 계약을 연결한다. 앱·테스트·마이그레이션 코드는 변경하지 않았다.

## 검증

Java21·새 전용 PostgreSQL16을 loopback에만 노출했다. 기존 실행 중 DB 두 개는 사용하지 않았다. `TOSS_SECRET_KEY`는 빈 값, `TOSS_BASE_URL`은 `http://127.0.0.1:9`로 외부 PG 호출을 막았다. 테스트는 기존 fake·mock·loopback을 사용한다.

| 확인 | 실행·대상 | 결과 |
| --- | --- | --- |
| 관련 회귀 | 아래 Gradle 명령, 새 빈 `ct_phase2_test` DB | 7클래스 136개, 실패·오류·건너뜀0 |
| DB 점검 SQL | #226 SQL, 빈 DB·특수문자 빈 스키마·합성 예약/결제/환불 데이터 DB·특수문자 데이터 스키마·없는 스키마 | 5조건 통과, 읽기 전용 on. 실행 전후 전체 스키마·이력·데이터 dump의 SHA-256 일치 |
| 코드 동일성 | `git diff ae797f78 5db45f0 -- src/main src/test build.gradle` | 차이 없음. 136개 회귀의 입력은 머지 후에도 동일해 재실행하지 않음 |
| 로컬 패키징 | `./gradlew bootJar --no-daemon` | 성공 |
| 실제 로컬 HTTP | 별도 새 `ct_phase2_app` DB, 패키징한 JAR·기존 `scripts/local-seed.sql` | 아래 18개 확인 통과, 결제·환불·환불 요청 행0 |
| 머지된 점검 도구 | 위 로컬 앱 DB에서 현재 `scripts/inspect-db-history.sql` 재확인 | 검증한 #226 SQL과 동일, 실행 전후 스키마·이력·데이터 해시 일치 |
| 머지 후 CI | [dev 5db45f0 CI](https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/41547564771) | SUCCESS. 로컬 실행과 별도 근거 |
| 연동 문서 | 변경 Markdown8개·상대 링크/앵커40개·`git diff --check` | 통과. 과거 기록 보존 |

```bash
DB_HOST=127.0.0.1 DB_PORT=64592 DB_NAME=ct_phase2_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:9 \
./gradlew test \
  --tests com.catchtable.reservation.ReservationRefundMigrationTest \
  --tests com.catchtable.reservation.ReservationApprovalMigrationTest \
  --tests com.catchtable.reservation.service.ReservationDepositConsumerTest \
  --tests com.catchtable.reservation.service.ReservationDepositLegacyTest \
  --tests com.catchtable.payment.service.PaymentPrepareServiceTest \
  --tests com.catchtable.reservation.service.ReservationRefundExecutionTest \
  --tests com.catchtable.reservation.service.ReservationRefundRestartTest \
  --no-daemon
```

64592는 해당 실행에서 배정된 임시 포트이며 재실행할 때는 새 전용 DB의 실제 포트를 사용한다. 136개는 마이그레이션2·원본 소비35·원본 미연결2·prepare54·환불 실행41·재시작2개다. 테스트 전용 원본 공급자를 사용한 유료 흐름을 실제 기능33 원본이나 Toss 검증으로 해석하지 않는다. 첫 컨테이너 준비에서 Unix socket readiness를 너무 일찍 확인해 psql이 종료코드2로 실패했다. 앱 테스트 전 TCP `SELECT 1`로 준비 확인을 바꾼 뒤 위 검증을 진행했다. 초기 준비 실패와 최종 로그·XML은 별도 보존했다.

실제 HTTP 18개는 health, 예약 가능 시간, 선점 전/중/반환 후 테이블 수량, 선점 생성/조회, 회원 헤더 누락401·타인 선점404, 일반 선점 prepare409, 선점 해제·반복 해제·해제 후 조회, 같은 자리 무료 예약, 무료 환불 예상액, 본문 없는 취소·반복 취소, 빈 결제 목록이다. 재조회로 만료시각이 바뀌지 않았고 반환 수량이 복구됐으며, 무료 취소 재요청의 취소시각은 같은 instant였다. 경계 시각·유료 보호·환불의 동시성/재시작은 위 회귀 범위이며 실제 HTTP에서 5분을 기다린 검증은 아니다.

## 프런트 대조와 남은 점

[reservationApi](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/reservation/api/reservationApi.ts)·예약 폼/상세 화면과 [ownerReservationApi](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/features/owner-reservation/api/ownerReservationApi.ts)를 읽었다. 예약 상태에는 PENDING_PAYMENT·EXPIRED가 있으나 확인한 호출부는 즉시확정 생성과 무키 취소다. 선점·prepare·confirm·환불 예상/실행·타이머는 연결되지 않았다. [axios](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/015f333aa2b2d4230560d69013136190b5ea2c87/src/shared/lib/axios.ts)는 회원 헤더만 추가한다. 프런트 코드·화면을 실행하거나 수정하지 않았다.

[기능33](https://app.notion.com/3d548d76fa3281d48af9ea164f74c563)의 완료는 P1 기본 정책이다. P2 예약금 설정과 실제 `ReservationDepositTermsReader` 공급자가 연결된 뒤 유료 생성부터 확인해야 한다. 기존 무료 기본값과 일반 선점을 임의 유료 설정으로 바꾸지 않았다.

실제 Toss·유료 프런트 흐름·공유 DB 사용 여부/이력·배포는 미확인이다. 공유 DB의 위치를 모른다는 답변을 공유 DB가 없다는 증거로 쓰지 않는다. V20 없는 populated V22 차단은 기존 회귀대로 유지하며 repair·번호 변경·outOfOrder·ignore를 하지 않았다. 상위 #65/#62/#63/#64/#68과 Notion 기능 상태는 전체 연동이 남아 유지한다. 검증 후 이번 로컬 앱과 전용 DB를 중지하고 기존 실행 환경은 보존했다.
