# 기능53~56 선점·결제 통합 후속

- 기준: `dev@6b257dc9ba9ac44c3ec5927266fae04dfec1c964`, `test/p2-hold-integration`.
- 정본: [53](https://app.notion.com/p/3d548d76fa3281f7834ee32be4c17f60), [54](https://app.notion.com/p/3d548d76fa3281a199abea9d67d1e616), [55](https://app.notion.com/p/3d548d76fa3281d4bc29d5f06ca81c68), [56](https://app.notion.com/p/3d548d76fa32813a94fbdec00b9f54e9).

아래는 범위 확장 전 1차 로컬 기록이다. 이후 사용자 결정으로 백엔드 API·V33·환불 아웃박스와 프런트를 추가 구현했다. 현재 결과는 [추가 기록](#2026-11-05-추가--정책-고지와-미확정-승인-환불)을 따른다. 각 실행의 테스트 수를 합산하지 않는다.

## 범위와 판단

이 대화의 통합 트리에서 네 기능의 원문·현재 코드·기존 테스트와 [앞선 실제 앱/Toss 기록](2026-11-05-p2-app-toss-verification.md)을 대조했다. 공통 Git 디렉터리의 이전 RESUME/final-summary/pause-and-cleanup 및 Notion API67개 상세 감사 결과도 재사용했다. 기능10·37·57·60·61과 닫힌 이슈를 다시 열지 않았다. 기능33의 실제 공급자 연결은 이미 반영돼 있으며 담당자 대기로 취급하지 않는다.

현재 백엔드에서 추가할 운영 코드 누락은 찾지 못했다. 기존 코어와 회귀를 재사용해 검증했고 백엔드 운영 코드·테스트·마이그레이션은 바꾸지 않았다. 사용자가 이 대화에서 프런트 범위를 확대해 기존 clean `frontend-p2`/`feat/p2-paid-flow@86c746e`의 미게시 화면을 보완했다. 추가 작업트리·에이전트는 만들지 않았고 rudderfish로 결정을 넘기지 않았다. Toss 새 인증은 사용자 결정대로 계속 보류한다.

## 기능별 확인

| 기능 | 백엔드 확인 | 프런트 로컬 후속 |
| --- | --- | --- |
| 53 임시 선점 | 실제 기능33 정책 → 최초 양수 금액/환불 정책과5분 스냅샷 → prepare 신규201/READY 재사용200 → 승인 보호·같은 슬롯 확정 | 기존409→hold→prepare→결제 화면을 재사용. SDK 초기화 뒤 서버의 같은 READY를 재확인하고0초·보호 상태에서 새 창을 차단 |
| 54 만료시간 표시 | `expiresAt/serverTime/remainingSeconds`, 만료1µs 전·정각·후 및 보호 중0초 PENDING_PAYMENT | 서버 시각 보정·0초 polling·새 시작 차단.0초만으로 실패·반환으로 안내하지 않음 |
| 55 실패·이탈 반환 | 명시적 해제/확정 실패만 반환, 결과 불명·승인 보호 유지. 재요청·롤백의 상태/이력/커밋 후 알림 중복 방지 | HTTP5xx·응답 유실을 결과 불명으로 보완. 승인 보호로 취소가 거절되면 재조회. 화면 이탈은 DELETE하지 않음. 첫50건 이후의 기존 결제도 탐색 |
| 56 만료 후 재예약 | 보호 없는 PENDING_PAYMENT만 정확한 경계에서 EXPIRED. 승인/만료 경합, 이전 지연 이벤트·요청과 새 선점 분리 | EXPIRED 안내→새 예약 경로. 같은 슬롯 재선점 뒤 이전 prepare/confirm/DELETE가 새 선점을 바꾸지 않음을 확인 |

예약·웨이팅의 상태/SSE, 소유권, PostgreSQL 잠금/제약, Asia/Seoul·Clock, 환불 REQUESTED/UNKNOWN 동결과 FAILED 키409 계약은 변경하지 않았다. `remainingSeconds=0`과 보호된 PENDING_PAYMENT는 함께 유지될 수 있다.

## 이번 백엔드 검증

Java21.0.12.1, 새 전용 PostgreSQL16.15 컨테이너 `ct-p2-hold-av4q6ovb`, loopback `127.0.0.1:59541`, 빈 `ct_p2_hold_test`를 사용했다. tmpfs·종료 시 자동 삭제다. 보존할 개발 DB·공유 DB·운영 DB를 사용하지 않았다. `TOSS_SECRET_KEY=`와 `TOSS_BASE_URL=http://127.0.0.1:9`, 외부 알림 비활성으로 실행했다. 비밀 키 파일은 읽지 않았다.

기존 테스트만 `./gradlew test --no-daemon --rerun-tasks`와 클래스/메서드 필터로 단일 실행했다. 전체 명령은 아래 산출물의 `test-command.txt`에 있다.

| 대상 | 실행 수 |
| --- | ---: |
| PaymentPrepareServiceTest | 54 |
| PaymentReconcileConcurrencyTest | 3 |
| ReservationConcurrencyTest | 6 |
| ReservationDepositConsumerTest | 35 |
| ReservationPaymentServiceTest | 46 |
| ReservationSettingsTest의 선점/만료/해제6개 메서드 | 6 |
| StoreDepositPaymentFlowTest | 5 |
| StoreDepositPolicyFlowTest | 11 |
| **단일 실행 합계** | **166** |

8클래스166개 모두 통과, 실패·오류·건너뜀0이다.31개 SQL(V32까지)이 적용됐고 전후 마이그레이션 SHA-256이 동일하다. 백엔드 소스 변경이 없어 관련 검사로 범위를 정했고 전체 build는 새로 실행하지 않았다. 시작 dev의 hosted CI41949101407/915개는 이전 CI 증거이며 이번166개와 합산하지 않는다.

## 프런트 보완과 검증

기존 프런트 다섯 로컬 커밋은 복구 bundle로 보존하고 같은 작업트리에서 필요한 경계만 보완했다. 이번 프런트 로컬 수정은 `ee9324cb4b3d4e7d7ccdf3bf2b5aea5c3b61cf8b`다. `npm test`22개 통과, `npm run lint`·`npm run build` 통과이며 기존 린트 경고2건은 유지한다. 최초 회귀 재현의9실패/17개 및 추가1실패/22개 로그도 보존했다. 테스트 실패를 숨기거나 이전 실행 수를 합산하지 않는다.

Playwright390×844의 별도 테스트 브라우저로7개 흐름을 통과했다. 실제 기능33 API→유료409→선점201→설정 변경 후에도10,001원·STANDARD·최초 주문/기한 유지→fake DONE 예약 확정, 명시적 반환/반복, Clock 전진 만료·동일 슬롯 재선점/과거 요청 차단, fake 확정 거절, 원래 기한 뒤0초 보호·sync 동일 슬롯 확정, 앱200을502로 치환한 뒤 결과 불명/동일 요청의 저장 결과 재사용, 다른 클라이언트의 승인 시작 후 취소409→재조회→0초 보호→sync다. 새 테스트 브라우저는 외부 호스트를 차단했고 실제 앱의 PG 구현은 fake다.

검증용 Clock의298초/301초 전진은 실제5분 경과 증거가 아니다. 실제5분 만료는 PR #249의 기존 근거를 유지한다.502 치환은 로컬 응답 유실 모사이며 실제 Toss·실제 통신 장애·앱 재시작 검증과 구분한다. 상세 변경과 화면별 결과는 프런트 `work-log/2026-11-04-paid-reservation-flow.md`의11월5일 후속에 남겼다.

## 산출물과 남은 범위

공통 Git 디렉터리의 `orca-artifacts/p2-hold-integration-20261105-av4q6ovb`에 최초/최종 로그·JUnit XML·명령·검증기·결과 JSON·모바일 캡처·기존 프런트 bundle을 보존한다. 기존 `p2-app-toss-20261105-56av1hkx` 산출물은 덮어쓰지 않았다.

검증 뒤 정리 단계에서 최종 상태 GET이500으로 실패했다. 전용 컨테이너는 이미 없고 원래 DB 포트가 닫힌 것을 확인해 DB dump는 만들지 못했다. 원인은 확인하지 않았다. 그 전에 완료한 테스트·브라우저 상태/결과·캡처는 보존했고 남아 있던 검증용 앱·프런트 프로세스와 두 포트를 종료했다. 정리 실패는 검증 성공 수에 포함하지 않는다. 재개 시 새 일회용 DB를 만든다.

실제 앱의 양수 Toss 승인/예약 확정·양수 환불·응답 유실 후 앱 재시작 복구는 인증 보류로 미수행이다. 이번 로컬 프런트 결과를 원격 dev 연결·hosted CI·공유 DB 적용·배포 또는 기능53~56 전체 완료로 쓰지 않는다. 외부 게시·머지는 별도 사용자 결정이다. 미완료/보류 범위와 현재 대화 공간을 보존하므로 이 작업트리를 닫지 않는다.

## 2026-11-05 추가 — 정책 고지와 미확정 승인 환불

### 사용자 결정과 범위

1차 결과 게시 전 사용자가 남은 구현을 함께 올리도록 지시했다. 결제 전 환불 기준을 최초 스냅샷으로 고지하며, 예약 미확정 승인(`refundRequired`)은 고객이 **남은 금액 전액 환불을 직접 요청**하는 방식을 선택했다. 트랜잭션 아웃박스로 접수·실행 사이의 중단을 복구하도록 했다. 이 결정은 일반 확정 예약의 고객 취소 수수료나 기능60·61의 완료 상태를 다시 정의하지 않는다.

프런트는 같은 `frontend-p2/feat/p2-paid-flow`에서 계속 작업한다. push와 dev 대상 두 PR 게시까지 사용자 허용을 받았으며 추가 구현/검증을 묶어 게시한다. 머지·배포와 새 Toss 인증은 허용으로 확대하지 않는다. Notion의 기존67개 API 상세 감사는 재사용했고 외부 원문은 수정하지 않았다. 신규 두 경로는 이번 추가 계약으로 명시한다.

### 구현과 설계

- prepare에 `refundPolicy/refundPolicyName/refundRules/refundPolicyNotice`를 추가했다. 기존 환불 계산의 규칙을 같은 enum에서 제공해 화면 고지와 계산이 어긋나지 않게 했다. 최초 양수 금액·정책·5분·READY 주문과 응답201/200을 유지한다. 프런트는 고지가 없으면 결제 시작을 막는다.
- 본인 고객의 미확정 승인 결제에 `POST/GET /api/user/payments/{paymentId}/unconfirmed-refund`를 추가했다. POST는 금액 본문 없이 필수 멱등키로 신규202/재조회200, GET은 최신200/없음204다. 원결제액에서 기환불액을 뺀 잔액 전부를 요청한다. 본인·역할·미확정/미입장 이력·종결 상태·환불 필요 표식·스냅샷·승인 보호·기존 진행 환불을 검사한다.
- 예약→해당 결제 행 잠금 안에서 `UNCONFIRMED_REFUND` 의도·동결·outbox를 원자적으로 저장한다. V33은 기존31개 SQL을 보존하며 허용 intent와 `reservation_refund_outbox`를 추가한다.
- 커밋 뒤 워커가 SKIP LOCKED/120초 lease와 시도 번호로 처리한다. PG는 잠금/트랜잭션 밖에서 기존 공통 환불 서비스를 통해 호출한다. 공통 요청 저장 전 중단은 outbox 지연 재시도, 저장 뒤 중단은 기존 조회·동일 키·15일 경계로 복구한다. outbox DELIVERED와 환불 COMPLETED는 서로 다르다. REQUESTED/UNKNOWN은 기존 재확인 대상에 남는다.
- 중복 전달 가능성을 전제로 기존 멱등성을 사용한다. [AWS의 트랜잭션 아웃박스 설명](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)과 같은 DB 원자성/커밋 뒤 전달 원칙이며, 이번 구현은 별도 메시지 브로커 대신 DB polling으로 공통 결제 엔진에 인계한다. 웨이팅/공통 결제 엔진 전체를 다시 만들지 않았다.
- 환불 완료는 기존 종결 예약·새 선점·취소 이력/SSE를 바꾸지 않는다. REQUESTED/UNKNOWN은 동결, FAILED는 해제하되 같은 실패 키409/PG 재호출 금지를 유지한다.
- 프런트 결제 상세에 전액 요청 확인창·접수/UNKNOWN/실패/완료·조회 실패 차단·3초 요청 조회·기존 sync를 연결했다. 응답 유실/새로고침에는 같은 키를 보존하고, 확정 FAILED 뒤 사용자가 확인한 재요청만 새 키를 만든다. MSW는 같은 응답을 제공하되 처리 완료의 시간 경과만 모사한다.

상세 요청/응답·스케줄러 기본값·적용 순서·롤백 제약은 [현재 계약](../docs/frontend/unconfirmed-refund.md)에 둔다. 백엔드 V33/API 적용 뒤 프런트를 연결해야 한다.

### 추가 구현의 최종 로컬 검증

JDK21.0.12.1, 새 tmpfs PostgreSQL16.15 `ct-refund-outbox-qikqjans`/`ct_refund_outbox_test`/loopback63423만 사용했다. 실제 Toss 설정은 빈 키·닫힌 loopback PG 주소이고 외부 알림은 껐다. 키 파일은 읽지 않았다.

| 실행 | 결과 |
| --- | --- |
| `./gradlew clean build --no-daemon` | **86클래스936개**, 실패/오류/건너뜀0, bootJar 성공 |
| 기존 Flyway31개 SHA-256 비교 + 새 DB 적용 | 기존 파일 변경0, V33 추가 후 SQL32개/최대 버전33 |
| `npm test -- --reporter=junit --outputFile=…` | **31개**, 실패/오류/건너뜀0 |
| `npm run lint`, `npm run build` | 성공. 기존 Calendar/StoreCard 경고2건, 새 경고 없음 |
| Playwright390×844 + 실제 앱/DB/fake PG | 아래4개 흐름 통과, 가로 넘침 없음 |
| Playwright + MSW 전용 별도 개발 서버 | 정책14필드/결제 전 고지→가상 미확정 승인→전액 환불 화면1개 smoke 통과 |

새 outbox20개와 실제 스케줄러1개는 원자 접수/롤백·권한·잔액·확정 실패/UNKNOWN·실행 전 중단·lease 만료·동시 소비·행 잠금 밖 PG·오래된 시도의 ack 차단·같은 키 동시 접수·기존 공통 요청 인계·14/15일 경계를 포함한다. 최종936개는 이전166/915/140개와 합산하지 않는다. 기본 테스트에서는 스케줄러를 꺼 결정적 실행을 유지하며 전용 스케줄러 테스트는 명시적으로 켠다.

| 브라우저 흐름 | 관측 |
| --- | --- |
| 최초 정책 고지 | 실제 기능33 API→10,001원 STANDARD 선점/prepare. 매장 설정을30,003원 STRICT로 바꾸고 새로고침해도 금액·정책·주문·기한 동일. fake 승인 후 원래 예약 확정 |
| 미확정 승인 전액 환불 | Clock301초 전진→EXPIRED→동일 슬롯 새 선점→늦은 fake 승인/sync. 고객 POST202→접수→새로고침→전액 COMPLETED. 같은 키200, 환불 효과1회, 새 선점 유지 |
| 응답 유실·재시작 | 접수202 응답을502로 치환하고 fake PG는 환불 효과를 별도 원장에 저장한 뒤 응답 유실. UNKNOWN/동결·키를 보존해 JVM 재시작. 기존 sync로 COMPLETED, 별도 fake 원장상 추가 환불 효과0, 새 선점 유지 |
| 확정 실패 후 명시 재요청 | fake 거절→FAILED/동결 해제. 같은 키409 PAYMENT_REFUND_FAILED. 사용자 확인 뒤 새 키로 재요청202→COMPLETED, 실제 fake 환불 효과1회 |

브라우저의 Clock 전진은 실제5분 대기가 아니다. fake PG 원장은 앱 DB와 별도 파일로 저장/복원했고 앱 DB 잔액에서 PG 원장을 재구성하지 않았다. 이는 **로컬 fake PG/JVM 재시작 복구**이며 실제 Toss 재시작 검증으로 쓰지 않는다. 1차7개 흐름은 이전 근거로 보존한다.

### 최초 실패와 보존

신규 계약 재현에서 백엔드2개가 정책 필드/없는 경로로 실패했고 프런트 신규7개 중6개가 실패했다. 구현 뒤 통과했다. 이후 스케줄러 테스트의 fixture가 `refund_required_reason` 없이 표식을 만들어 DB 제약으로1개 실패했고 fixture를 수정했다. 프런트 첫 build는 목 계산에 사용한 dayjs timezone 플러그인 누락으로 실패해 목 파일에서 명시적으로 로드했다. 최종 전체 결과는 위 표다.

브라우저 재시작 검증기의 첫 시도는 지원하지 않는 DB_URL을 사용해 연결 실패/ready timeout이었다. fallback 로컬5433에는 listener가 없었다. DB_HOST/DB_PORT/DB_NAME을 전용 DB로 고쳤고, 재개 중 앱 준비 전 조회·로그인의 실패도 보존했다. 새 환불을 다시 만들지 않고 저장된 UNKNOWN 요청·동일 전용 DB·별도 fake PG 원장에서 재개해 복구를 확인했다. 이 실패를 앱 회귀나 성공 건수로 세지 않는다.

MSW smoke의 최초 시도는 메모리 fixture를 저장하지 않은 채 전체 페이지를 이동해 늦은 승인 fixture가 사라져 실패했다. 목의 기존 저장 경로를 거치도록 검증기를 수정한 뒤 통과했다. 제품 코드는 추가 변경하지 않았으며 실제 앱4개와 별도 결과다.

로그·JUnit·마이그레이션 비교·검증기·부분/최종 JSON·모바일 캡처는 공통 Git 디렉터리 `orca-artifacts/p2-refund-outbox-20261105-qikqjans`에 남긴다. 1차 산출물과 기존 실제 PG 기록을 덮어쓰지 않는다. 현재 PR의 CI는 게시 후 해당 head에서 확인하며 이전 dev CI를 이번 결과로 보고하지 않는다.

이번 전용 DB는 최종 상태·pg_dump와 복원 목록 확인을 마친 뒤 종료했다. 검증용 앱·실제 API용/목용 프런트와 모든 전용 포트의 종료, 컨테이너 제거를 재확인했다. 첫 정리 확인은 HTTP 포트가 닫힌 직후 Java 종료가 끝나기 전 PID를 검사해 실패했고, 종료 후 다시 확인했다. 강제 종료·다른 DB 정리는 하지 않았다. `cleanup.json`에 최종 결과를 남겼다.

실제 Toss 정상 앱 승인/예약 확정·양수 환불·실제 PG 응답 유실/재시작 복구는 인증 보류로 미수행이다. 원격 dev 머지·공유 DB 적용·배포 및 기능 전체 완료도 별도다. PR 머지 전이며 보류 업무가 있으므로 두 작업트리를 보존한다.
