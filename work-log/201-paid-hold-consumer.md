# 예약금 서버 원본 소비 경계와 유료 선점 생성

- 작업일: 2026-10-29
- 관련: [#201](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/201), 상위 [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), 선행 [PR #198](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/198), 별도 문서 [#202](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/202)
- 정본: [기능10](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b), [기능53](https://app.notion.com/p/3d548d76fa3281f7834ee32be4c17f60), [기능33](https://app.notion.com/p/3d548d76fa3281d48af9ea164f74c563)
- 브랜치: `feat/201-paid-hold-consumer`. 최신 확인 `dev@985e55cf34c77176377ee8b165a2df8868bbede9`에서 선행 `f81bcaa95de0244d76ff4a2d40e9d89165d06c55`를 정상 merge했다. merge는 `1cad0cc74d2294dbed3e2cc5ba8891495fdf9766`이며 다른 기여자의 매장 페이징·이미지와 선행 이력·문서를 유지했다.
- 최종 앱·테스트 검증 커밋: `c76174034f1c4dcfcd1df6eb579e9580c088f353`. 전체 빌드 뒤 이 작업 기록만 추가한다.

## 배경과 범위

기존 prepare는 저장된 유료 스냅샷을 사용하지만 공개 선점 생성은 매장의 예약금 원본을 소비하지 않았다. 예약 측에 작은 서버 원본 계약을 두고, 기존 배정 경로 안에서 최초 금액·정책을 저장하며 알려진 유료 매장의 즉시확정 우회를 차단한다.

현재 공유 dev와 공개 `feat/reservation-policy`의 `ReservationPolicyRequest`는 이용시간·슬롯·유예·예약 가능 일수·마감만 정의한다. 기능33의 P2 예약금 저장·설정·조회 어댑터는 없으며 방준혁 담당으로 남긴다. 이번에 매장 필드·DTO·API·seed·기본 금액/정책·운영 가짜 공급자를 만들지 않는다.

## 판단과 실제 변경

2026-10-29 사용자 결정은 **설정 리더 연결 전 기존 무료 즉시확정과 일반 홀드 흐름 유지**다. 리더가 설치됐는데 확인할 수 없는 원본을 무료로 추정하는 것은 허용하지 않는다. 유료 매장의 유효한 직접 생성 요청은409로 선점→준비→승인 경로를 안내하며 자동으로 선점으로 바꾸지 않는다.

- `ReservationDepositTermsReader.read(Store lockedStore)`와 불변 `Terms`만 추가했다. FREE는 `false/0/null`, PAID는 `true/양수 long/FLEXIBLE·STANDARD·STRICT`다. 모순된 조합은 생성 시 거절한다. 리더의 null/실패/잘못된 값은 `409 RESERVATION_DEPOSIT_TERMS_UNAVAILABLE`이며 무료·기본값으로 대체하지 않는다. 운영 구현체는 등록하지 않았다.
- `ReservationService.allocate`가 기존 매장 `FOR SHARE` 안에서 원본을 읽고, 기존 예약 시간 검증과 테이블 `FOR UPDATE`·시간 중복 제약을 재사용한다. 미래 어댑터는 같은 트랜잭션의 서버 원본을 읽고 설정 쓰기도 같은 매장 잠금과 직렬화해야 한다. 외부 통신·결제 행 선조회·두 번째 배정 알고리즘은 추가하지 않았다.
- 유료 `POST /api/user/reservations/holds`는 기존 `Reservation.held(..., amount, policy)`를 사용해 테이블·최초 금액/정책·원래 `requestedAt + 5분`을 원자 저장한다. 금액은 인원수와 무관한 건당 값이다. 이 요청은 결제 주문·PG 호출·SSE를 만들지 않는다.
- 리더로 유료임을 확인한 `POST /api/user/reservations`는 만료 정리·테이블 배정·생성 전에 `409 RESERVATION_PAYMENT_REQUIRED`다. 메시지는 “예약금 결제가 필요합니다. 선점 생성 → 결제 준비 → 승인 경로를 이용해 주세요.”다. 무료 직접 확정은0/null, FREE 또는 미연결 리더의 기존 일반 홀드는null/null을 유지한다. 무료 홀드 거절·자동 확정 정책은 추가하지 않았다.
- 요청 DTO는 기존 `storeId/reservationStartAt/partySize`뿐이다. 추가 JSON의 예약금 사용 여부·금액·정책·회원·테이블·원래 시각은 서버 원본에 영향을 주지 못한다. 직접 생성의 목 고객1, 홀드/결제의 `X-Member-Id`, 점주 취소의 목2·필수 사유를 유지했다.
- 최초 스냅샷 이후 prepare·승인·재확인·예약 변경·환불·취소 구현은 변경하지 않았다. 원래5분·동일 요청의 늦은 DONE 확정1회·READY/TIMEOUT/FAILED 비확정·알림 커밋 후1회·환불 중 변경 동결을 기존 서비스로 검증했다. 관련 [prepare 기록](195-payment-prepare-snapshot.md), [스냅샷·승인 기록](65-deposit-snapshot.md), [환불 실행 기록](188-reservation-refund-execution.md)을 따른다.
- own 소스 커밋에서 `docs/frontend`를 수정하지 않았다. 선행 merge가 가져온 문서는 유지한다. 조정자의 2026-10-29 후속 지시로 #202 문서 후보 중복 작성은 생략하고 조정자가 별도 문서 worktree에서 작성한다. 소비 경계·실제 API 계약은 외부 `consumer-api-contract.md`로 전달한다.

## 검증

Java21.0.12.1, 전용 PostgreSQL16.15 컨테이너 `issue201-paid-hold-20261029-pg16`(ID `a316ecd1400cad57d166875a56974fe27efef14c37c8f3d9c488936b6689ace5`), `127.0.0.1:63255`, 기본 `max_connections=100`을 사용했다. 기존 컨테이너·공용5433·개발 DB는 사용하지 않았다. `TOSS_SECRET_KEY`는 빈 값, 기본 Toss 주소는 loopback이며 테스트는 fake/mock/loopback만 사용했다. 새 테스트는 실제 AtomicReference MutableClock과 클래스 종료 시 컨텍스트 반환을 사용한다.

| 확인 | 실제 실행·대상 | 결과 |
| --- | --- | --- |
| 첫 소비 경계 실행 | `./gradlew test --tests '*ReservationDepositConsumerTest' --tests '*ReservationDepositLegacyTest' --no-daemon`, `worker201_target` | 37개 모두 fixture 준비 실패. GENERATED ALWAYS 회원 ID 삽입에 테스트 전용 `OVERRIDING SYSTEM VALUE`를 추가. 운영 식별자/스키마 변경 없음 |
| fixture 수정 후 | 같은 명령·DB | 37개 중8개가 JDBC map의 Timestamp를 OffsetDateTime으로 잘못 캐스팅한 테스트 assertion 실패. instant 비교로 수정. 앞선 두 실행 로그·XML 보존 |
| 새 경계 테스트 | 같은 명령·DB | 2클래스37개, 실패/오류/건너뜀0. 목 고객1 SSE 관찰 추가 뒤 실행도37개 통과 |
| 기존 관련 회귀 | `./gradlew test`에 prepare·승인·재확인·환불·취소·설정·경합·시간·마이그레이션12개 필터와 `--no-daemon`; 전체 인자는 외부 `checks/04-regressions/command.json` | 14클래스280개, 실패/오류/건너뜀0 |
| 최종 전체 검증 | 같은 컨테이너에서 새로 만든 `worker201_final`, 실행 전 public 테이블0개. `./gradlew clean build --no-daemon` | **68클래스758개, 실패/오류/건너뜀0**, bootJar 성공. 이 최종 수만 사용하며37/280·과거708개를 합산하지 않음 |
| 기존 migration | 25개 파일 SHA-256 전후 대조, V20/V25/V26 필수 해시 확인 | 모두 보존, 새 migration 없음. V20 없는 populated V22 업그레이드 거절·기존 행/이력/스키마 보존 테스트도 전체 빌드에서 통과. repair/rename/outOfOrder/ignore 미사용 |
| 기존 작업 보존 | betta588개 파일 SHA-256·10개 WIP 경로, backing checkout, 공개8개 자체 소스 head와 공통179 head 대조 | 요청 밖 파일/브랜치 수정 없음. 최종 외부 보존 보고서에 전후 결과 보관 |

새 테스트는 test-only 서버 공급자를 저장된 매장 ID로 설정하고 **공개 홀드 API부터** 시작한다. 원본 저장·고객 필드 무시·유료 직접409와 `expireHolds` 호출0·무료/미연결 회귀·실패 원본으로 행 생성0, 매장 설정 쓰기 대기와 커밋 후 새 원본, 마지막 테이블 경합·용량을 확인했다. prepare와 승인 시작의 원래 만료1µs 전/정각/후, READY 주문·금액·만료 재사용, 보호 중 새 prepare/해제409, 같은 슬롯의 늦은 DONE과 별도 연결에서 커밋 후 SSE1회도 검증했다.

별도 시나리오로 공개 생성→prepare→fake DONE→점주 시각/인원 변경→현재 예약일 KST·최초 STANDARD 정책의 원 단위 내림→부분 환불→누적 차감→고객 취소, 점주100% 취소·재요청, REQUESTED/UNKNOWN의 변경/입장/노쇼/일괄 완료/다른 취소 동결을 확인했다. 이는 **기능33 운영 어댑터가 연결된 실제 유료 E2E가 아니다**.

검증한 `src/main` tree는 `ab9ff18d140ea838efe19d5c989ef536c1cff957`, `src/test` tree는 `05c9e9fe2c56b0ffa7db85e61e88342bdbfac0fc`, `build.gradle` blob은 `4e7c995e994b5758b07b85ecae73c43177628355`다. bootJar SHA-256은 `50e21bf069544c73cbc5b91c34c71a61f0b919042b669b695879da2bba70b5b6`다. 전체 실행의333개 소스/테스트/빌드 입력과604개 빌드 산출물 해시, 소스/빌드 archive, JAR, 명령·완전한 로그·실행별 XML·클래스 합계는 외부 `own-paid-hold-followup/worker201/report.md`와 함께 보관했다. 작업 기록만 추가한 뒤 같은 앱/테스트/빌드 입력을 다시 대조한다.

## 남은 점

로컬 구현·검증·커밋과 외부 소비/API 계약 정리까지의 결과다. 조정자가 dev 대상 **일반 소스 PR(Ready for review) 게시·정확한 head CI**, 별도 #202 문서 작성·PR·Notion 갱신을 담당한다. 소스는 미머지이며 이 기록으로 #201/#65를 닫거나 배포 완료로 표시하지 않는다.

기능33 실제 설정 저장/조회 연결 전에는 사용자 결정대로 기존 무료/일반 홀드 경로다. 설치된 리더 오류는409다. 실제 기능33 원본 연결, 공유 V20 적용 이력·rollout, 실제 Toss·프런트/JWT·배포는 남아 있다. 빈 DB 성공은 기존 DB 업그레이드 증거가 아니다. 전용 DB는 증거 수집 뒤 중지하고 다른 DB/작업 트리는 유지한다.
