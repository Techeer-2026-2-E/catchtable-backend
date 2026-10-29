# 저장된 유료 선점의 결제 준비와 READY 주문 재사용

- 작업일: 2026-10-29
- 관련: [#195](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/195), 상위 [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), 별도 문서 [#196](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/196)
- 정본: [기능10](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b), [prepare API](https://app.notion.com/p/0ba48d76fa328309ab3a018b1b5fed0f), [confirm API](https://app.notion.com/p/39b48d76fa328319a4a181bc187e8970)
- 브랜치: `feat/195-payment-prepare-snapshot`. 최초 `dev@20f699f77f0de68d3e77c03e77cdb6e7e8c7e7a0`에서 PR #192 head `80ef5452d065689693f35e2ca16b208c26d88db9`를 정상 merge했다. 이후 `dev@9da5d70d0afa296dad25fc689413c04c76a89272`를 정상 merge해 다른 기여자의 근처 매장·목록 이미지 변경도 보존했다.
- 최종 앱 검증 커밋: `3080466a18aa18d6f7fb334771b80273b6731c1b`. 이 기록은 검증 뒤 문서만 추가했다.

## 배경과 목표

승인·재확인·환불 후보는 기존 주문을 검증하지만 저장된 유료 선점으로 최초 주문을 준비하는 경로가 없었다. 신뢰할 수 있는 서버 생성 경로가 이미 저장한 양수 예약금·환불 정책 스냅샷으로만 `POST /api/user/payments/prepare`를 제공한다. 기능33의 매장 설정·유료 선점 공개 생성 경로는 이번에 연결하지 않는다. 현재 무료 선점에 임의 금액·정책을 채우지 않는다.

## 판단과 근거

2026-10-29 사용자 결정에 따라 같은 유효 선점의 `READY`는 금액·주문·원래 만료시각 그대로 재사용한다. 승인 시작·결과 불명·완료는 409로 기존 결제 상태 조회를 안내하며 새 결제를 만들지 않는다. `FAILED`/`CANCELED`나 여러 결제 행에는 새 재결제 정책을 만들지 않고 충돌로 거절한다.

기존 [스냅샷·승인 기록](65-deposit-snapshot.md), [재확인 기록](142-payment-reconcile.md), [환불 실행 기록](188-reservation-refund-execution.md)을 따른다. 준비는 예약 행→결제 ID 순으로 잠근 짧은 트랜잭션이며 결제를 먼저 managed entity로 읽지 않는다. 승인·환불의 바깥 트랜잭션 없는 외부 호출, 원래 5분 안 시작한 같은 시도의 늦은 DONE 확정, 환불 중 변경 동결, 커밋 후 SSE 계약은 변경하지 않았다.

## 실제 변경

- `PaymentPrepareRequest`는 양수 `holdId`만 정의한다. 현재 모델의 `holdId == reservationId`이며 고객은 숫자 `X-Member-Id`와 CUSTOMER 유형·소유권으로 검사한다. 다른 고객의 선점은 `404 RESERVATION_NOT_FOUND`다. 추가 JSON의 금액·정책·주문·만료·회원 필드는 기존 Jackson 규칙대로 무시되어 서버 원본을 바꾸지 못한다.
- 저장된 양수 금액·유효 정책·원래 `requestedAt + 5분` 만료시각, 미입장 `PENDING_PAYMENT`, 잠금 취득 후 현재 시각이 원래 만료 전임을 검사한다. 무료/누락/불일치/만료/종료 데이터로 주문이나 이력을 만들거나 예약을 되살리지 않는다.
- 새 주문은 `ct-` + UUID(39자), 주문명 `예약금`, 스냅샷 금액으로 기존 `Payment.ready`를 저장하고 `READY → READY`, `USER`, `결제 준비` 최초 이력을 1건 남긴다. 스키마·PG 클라이언트·상태 머신은 추가하지 않는다. 준비는 네트워크 호출과 SSE를 발행하지 않는다.
- 신규 `201`, 유효 READY 재사용 `200`. 두 응답은 `paymentId`, `reservationId`, `holdId`, `orderId`, `orderName`, `amount`, `status`, `expiresAt`, `serverTime`, `remainingSeconds`다. 마지막 값은 기존 선점 DTO처럼 남은 초를 내림하고 0 이상으로 제한한다. DB가 반환한 UTC 만료시각은 원래 시각과 같은 instant다.
- 재사용은 같은 회원·예약·금액과 승인 전 주문 바인딩을 검증하고 기존 결제·예약·이력을 수정하지 않는다. 승인 시작/격리 또는 `TIMEOUT`/`DONE`/`PARTIAL_REFUNDED`/`REFUNDED`는 `409 PAYMENT_STATUS_CHECK_REQUIRED`와 “기존 결제 상태를 조회해 주세요”를 반환한다. 기존 목록·상세·sync 경로로 확인하고 새 prepare/결제를 만들지 않는다.
- 잘못된 원본·불일치 READY·여러 행은 `409 PAYMENT_MISMATCH`, 시각·예약 상태·기존 FAILED/CANCELED는 `409 INVALID_PAYMENT_STATUS`, 환불 동결은 기존 `409 REFUND_DATA_NOT_READY`다. 입력400·회원 식별401·회원 유형403·회원/선점404 규칙도 유지한다.
- 저장소의 `docs/frontend`는 직접 수정하지 않았다. 최신 dev 문서에 새 계약을 반영한 교체 후보는 별도 #196용 외부 산출물로 전달한다. 선행 merge에서 들어온 문서·코드·이력은 보존했다.

## 검증

Java 21.0.12.1과 이번 작업만의 PostgreSQL 16.15 컨테이너 `issue195-prepare-20261029-pg16`(ID `14d2160a066a87c602a06e1c90eddb8e805e97fd4ab540f5b422370952804e87`, localhost `54343`)를 사용했다. 기본 `max_connections=100`을 유지하고 다른 컨테이너·5433·공용 DB는 사용하지 않았다. `TOSS_SECRET_KEY`는 빈 값, 기본 Toss 주소는 loopback으로 두고 fake·mock·loopback만 사용했다. 새 테스트는 원자적 MutableClock과 클래스 종료 시 컨텍스트 반환을 사용한다.

| 확인 | 실제 명령·대상 | 결과 |
| --- | --- | --- |
| 첫 prepare 실행 | `./gradlew test --tests '*PaymentPrepareServiceTest' --no-daemon`, `prepare_targeted` | 54개 중 2실패. UTC로 정규화된 만료시각과 +09:00 문자열/리스트를 비교한 테스트 기대값 문제. instant 비교로 수정. 전체 실패 로그·XML 보존 |
| 수정 후 prepare | 같은 명령·DB | 54개, 실패/오류/건너뜀 0 |
| 결제·승인·재확인·환불·취소·선점·설정·무료 예약 회귀 | `./gradlew test`에 12개 `--tests` 필터와 `--no-daemon`; 전체 인자는 외부 `logs/03-regressions/command.json` | 29클래스 424개, 실패/오류/건너뜀 0. 앞선 실행과 중복되므로 합산하지 않음 |
| 최종 전체 검증 | 같은 컨테이너의 새 `prepare_final` DB(실행 전 public 테이블 0개), `./gradlew clean build --no-daemon` | 66클래스 **708개**, 실패/오류/건너뜀 **0**, bootJar 성공 |
| 원본 마이그레이션 | 25개 파일 전후 SHA-256 비교 | 모두 동일. V20/V25/V26 유지, 새 마이그레이션 없음 |
| V20 없는 populated V22 | 전체 빌드의 `ReservationRefundMigrationTest.populatedV22WithoutV20StillRejectsUpgradeBeforeAnyNewSchemaChange` | 예상 Flyway 실패를 확인하고 기존 행·V22 이력·스키마 보존. repair/outOfOrder/ignore 사용 안 함 |
| 기존 변경 보호 | 원본 betta 588개 파일 SHA-256·10개 WIP 경로 대조, 기존 공개 head 보존 | 변경 없이 보존. 다른 source branch/worktree 수정 없음 |

새 54개는 MockMvc·실제 PostgreSQL로 소유권/역할/입력, 서버 금액, snapshot 제약, 전체 행이 그대로인 READY 재사용, 8개 동시 prepare의 주문/최초 이력 1건, 원래 만료 1µs 전·정각·후, 예약 잠금 대기 중 결제 변경·만료, 승인 진행/결과 불명/격리/완료 충돌을 검사한다. 신뢰된 유료 fixture의 prepare→기존 confirm DONE→정책상 환불→고객 취소 및 멱등 취소도 통과했다. 외부 승인 중 잠금·바깥 트랜잭션 부재, 같은 원래 슬롯의 늦은 확정 1회·커밋 후 알림, 무료 공개 선점이 결제 대상이 되지 않음을 확인했다. 이는 **유료 선점 공개 생성부터의 E2E가 아니다**.

최종 `src/main` Git tree는 `3b4e9ff4266c42599ac2fe35c9d209d255aa5749`, `src/test`는 `2015aa74398d823d54b3e8de5611597013db1c07`이다. build.gradle blob은 `4e7c995e994b5758b07b85ecae73c43177628355`다. 이 기록만 추가한 뒤도 같은 트리를 유지한다. `build/libs/catchtable-0.0.1-SNAPSHOT.jar` SHA-256은 `b72a0433bd38ff586e5adecdb208a1d79db70b6f90ab0bae4b840a669863bbfe`다.

전체 명령 로그·종료 코드, 실행별 XML/클래스 합계, 323개 소스·테스트와 583개 빌드 파일의 해시, 소스/빌드 archive, JAR, 보존 검증은 외부 `publication-and-prepare-followup/worker195/`의 `report.md`와 함께 보관했다. 최종 전체 결과만 708개이며 과거 #192의 628개나 중복 targeted 수를 합치지 않았다. 이 기록 추가 후 링크·`git diff --check`와 검증한 소스/테스트/빌드 입력 동일성을 확인한다.

## 남은 점과 후속 작업

로컬 구현·검증 결과다. source Draft PR 게시·정확한 source head CI·별도 #196 문서 PR·Notion 갱신은 조정자가 맡으며 이 기록은 소스 merge 또는 #195/#65 종료를 뜻하지 않는다. 기능33 설정과 실제 유료 선점 생성, 공유 V20 이력·rollout, 실제 Toss·프런트/JWT·배포는 미확인/미완료다. 빈 DB 빌드 성공은 기존 DB 업그레이드 증거가 아니다. 이번 전용 컨테이너는 증거 수집 후 중지한다.
