# 기능53~56 선점·결제 통합 후속

- 기준: `dev@6b257dc9ba9ac44c3ec5927266fae04dfec1c964`, `test/p2-hold-integration`.
- 정본: [53](https://app.notion.com/p/3d548d76fa3281f7834ee32be4c17f60), [54](https://app.notion.com/p/3d548d76fa3281a199abea9d67d1e616), [55](https://app.notion.com/p/3d548d76fa3281d4bc29d5f06ca81c68), [56](https://app.notion.com/p/3d548d76fa32813a94fbdec00b9f54e9).

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
