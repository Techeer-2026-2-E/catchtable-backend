# 기능54·55·56 선점 만료·반환·재예약 경계 검증

- 작업일: 2026-11-03
- 관련: [#62](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/62), [#63](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/63), [#64](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/64)
- 명세: [기능54](https://app.notion.com/3d548d76fa3281a199abea9d67d1e616), [기능55](https://app.notion.com/3d548d76fa3281d4bc29d5f06ca81c68), [기능56](https://app.notion.com/3d548d76fa32813a94fbdec00b9f54e9)
- 기준: `origin/dev@54119eb3c236f24b4a77108ca0c1c19d51abc3d1`, 독립 `test/62-hold-lifecycle-boundaries` 브랜치

## 범위와 변경

백엔드 완료 조건과 기존 테스트를 대조했다. 승인 진행·TIMEOUT 보호, 확인된 FAILED/CANCELED 반환, 늦은 DONE의 원래 슬롯 확정, 실패 후 재선점·반복 재확인은 이미 검증하고 있어 운영 코드와 정책은 바꾸지 않았다. 빠진 조합만 기존 테스트에 보충했다.

- `ReservationPaymentServiceTest`: 만료 1μs 전 GET을 반복해 최초 기한·KST 서버 시각·PENDING_PAYMENT·남은 시간 0초를 확인한 뒤 같은 슬롯을 늦게 확정한다. 남은 초의 내림은 실제 만료 판단을 대체하지 않는다.
- 같은 테스트의 신규 승인 차단과 반복 HTTP DELETE를 만료 정각·1μs 후에 확인한다. 1μs 전 명시적 DELETE는 CANCELLED로 종료하고 재승인을 차단한다.
- 기존 CANCELLED/EXPIRED 픽스처를 재사용해 같은 자리의 새 선점 생성 후 이전 결제의 늦은 DONE sync·반복 재확인·confirm·DELETE가 새 선점의 상태·테이블·시간·기한을 바꾸거나 SSE를 발행하지 않는지 확인한다. 이 픽스처는 과거 종료 상태 보정 검증이며 정상 승인 보호를 강제로 해제하는 API를 추가한 것이 아니다.
- `ReservationConcurrencyTest`: 기존 빈 테이블의 경합 3가지를 유지하고, 미승인 만료 선점이 있는 같은 테이블·시간의 선점/예약 경합 3가지를 추가한다. 이전 행은 EXPIRED가 되고 새 요청은 정확히 하나만 성공해야 한다.

## 로컬 검증

Java 21.0.12, 새 일회용 PostgreSQL 16.15(`ct-p2-hold-lifecycle-8fadb495b2f1`), 빈 `ct_p2_hold_test`, 자동 배정된 `127.0.0.1:63324`를 사용했다. TCP `SELECT 1` 후 테스트했다. Toss 키는 빈 값, base URL은 `http://127.0.0.1:9`이며 기존 mock/fake만 사용했다. 63324는 이번 실행의 임시 포트다.

| 실행 | 결과 |
| --- | --- |
| 수정 전 관련 9클래스: 예약 결제·설정·동시성·가용 수량·컨트롤러, 내역 2클래스, 결제 재확인·재확인 동시성 | 127개 통과, 실패·오류·건너뜀 0 |
| 보강 후 변경 테스트 2클래스 첫 실행 | 52개 중 2개 실패. 늦은 DONE 뒤 confirm의 오류를 INVALID_PAYMENT_STATUS로 잘못 예상한 새 단언 실패 |
| 기존 계약인 PAYMENT_RECONCILIATION_REQUIRED로 단언 정정 후 같은 2클래스 | 52개 통과, 실패·오류·건너뜀 0 |

변경 후 명령은 아래와 같다. 수정 전 9클래스의 정확한 명령, 실행별 전체 로그·XML·입력 트리는 공통 Git 디렉터리의 `orca-artifacts/run_8fadb495b2f1/p2-hold-lifecycle/`에 보존했다. 수정 전 127개와 변경 후 52개를 합산해 단일 실행 수로 보고하지 않는다.

```bash
DB_HOST=127.0.0.1 DB_PORT=63324 DB_NAME=ct_p2_hold_test \
DB_USERNAME=catchtable DB_PASSWORD= \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:9 \
./gradlew test \
  --tests com.catchtable.reservation.service.ReservationPaymentServiceTest \
  --tests com.catchtable.reservation.service.ReservationConcurrencyTest \
  --no-daemon --max-workers=2 --console=plain
```

`git diff --check`와 기존 마이그레이션 SHA-256 동일성을 확인했다. 새 마이그레이션·설정·운영 소스 변경은 없다.

## 남은 범위

실제 기능33 설정 공급자·Toss·프런트 타이머/이탈 요청 발생·공유 DB·배포는 검증하지 않았다. 전체 build와 최신 dev 통합 검증은 조정자가 수행한다. 검증 중 조정자가 PR #235의 dev 병합(`f12ebb26ce6f13417ab359ea7fdaa38717bf1bc3`)을 전달했지만, 이 작업은 merge/rebase 없이 최초 입력을 유지했다. PR #235에 의존하는 새 코드는 없다.
