# 웨이팅 SSE 작업·시도 영속화 — 2026-11-11

## 배경과 범위

승인된 후속안에 따라 웨이팅 SSE를 같은 업무 TX의 영속 root와 전용 worker 전달로 변경했다. 기존 V36 관찰은 AFTER_COMMIT 이전/전송 이후 공백을 복구하지 못했고 메모리 관찰 큐도 프로세스 종료를 견디지 못했다. 업무·알림 작업 저장 실패는 함께 rollback하고, 결과를 확정할 수 없는 시도는 UNKNOWN으로 남기는 보장이다. 고객 수신 성공, 네트워크 exactly-once, 다중 인스턴스 라우팅은 약속하지 않는다.

542파일 시작 기준선에는 이전 통합 변경 86파일이 이미 포함되어 있었다. 이 작업의 delta는 그 기준선과 별도로 비교하며 이전 결제·예약·검색 변경을 재구현한 것으로 세지 않는다. 로컬만 작업했고 commit/push/PR/merge/배포/외부 공급사 호출은 하지 않았다.

## 판단과 구현

- 직접/순번 이벤트의 동기 listener가 root를 기록하고 TX 밖 직접 전송을 거절한다. 기존 AFTER_COMMIT 웨이팅 전송을 차단해 한 전달 주체만 유지한다. 신규 호출/재호출/재전송/응답/연기/착석/종료·만료 원인과 root 수는 [현재 문서](../docs/waiting-sse-durability.md)에 목록화했다.
- V38에서 root/target/attempt/append observation과 admission counter를 추가했다. 이전 V1~V37 범위의 **실제 36파일**은 SHA256가 같으며 V23 번호는 기준선에도 없다. 과거 backfill이나 V36 trigger 변경은 없다.
- 처리 시점 기대 수신자/대상 수·확장 시각을 한 TX에 저장한다. 연결0 생략 대상과 확장완료한 대상0을 구분한다. 연결 세대 UUID와 대상당 고유 attempt, 저장 관찰 키로 재연결/중복 저장을 구분한다.
- claim/expand/mark/authorize/result 저장은 짧은 TX이고 send는 DB 연결 밖이다. 전용 scheduler 1개 스레드·1개 실행 슬롯으로 예약/PG scheduler와 분리한다.
- lease 만료한 MARKED는 UNKNOWN이며 자동 재전송하지 않는다. READY만 재처리한다. 결과 저장만 3회 재시도하고, 늦은 확정 결과는 UNKNOWN 관찰을 지우지 않고 현재 상태를 해소한다. 실제 send와 고객 수신은 별도다.
- 완료 이력까지 보존 root 수를 세어 동시 admission을 제한한다. 포화면 전체 업무 rollback + 503/Retry-After 30. 이력 자동 삭제가 없어 언젠가 용량에 도달하므로 운영 전 보존 기간·크기 선택이 필요하다. 한도 증가 후 이력 보존·신규 수용 재개를 검증했다.
- V36 메모리 관찰 writer는 main에서 제거하고 과거 저장 회귀용 src/test fixture로 옮겼다. 제한 운영 SQL은 원문 payload·민감 정보를 출력하지 않는다.

## 검증 환경과 결과

전용 PostgreSQL16 컨테이너 `ct-sse-l-20261111`, `127.0.0.1:55436/ct_sse_l`, 소유 label `task_c48c1d917044`. 기존 이미지 `sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54`를 `--pull=never`로 사용했다. Java `temurin-21.0.12.1`, Gradle wrapper 9.7.1, `--offline --no-daemon --max-workers=2 -Dorg.gradle.java.installations.auto-download=false`로 실행했다. 관련 테스트 실행 전 19084가 비어 있음을 확인했다.

- 첫 신규 회귀 15개 중 3개 실패: fixture의 created_at 누락 2개와 Spring proxy spy 구성 1개. 수정 후 통과했다.
- 첫 관련 회귀 309개 중 10개 실패: 동기 전송/내부 사건 record 동일성/연결 저장 구조의 옛 기대값. 비동기 worker를 명시 실행하고 원장·실제 SSE 결과로 변경한 뒤 310개 통과했다. 테스트를 skip하지 않았다.
- 관련 범위를 포함한 32클래스 **370개, 실패/오류/skip 0** 및 bootJar 성공. 웨이팅/알림 전체, 영업시간 충돌, 예약 결제 서비스 회귀를 포함한다.
- 마지막 용량 상향 회귀 추가 후 영속성/마이그레이션 **23개, 실패/오류/skip 0**으로 재실행했다. 관련 고유 테스트는 합계 371개이며, 반복 실행을 별개의 테스트로 합산하지 않는다.
- 마지막 scheduler 경계 회귀는 실제 waitingSseScheduler 스레드에서 send를 멈춘 동안 Hikari 2개 풀의 active=0과 업무 호출·공통 reservation 조회·별도 PG scheduler payment 조회 진행을 확인한다. 23개 경계 회귀를 다시 실제 실행해 모두 통과했다.
- fresh V38과 V37→V38을 실제 Flyway로 실행했다. 기존 모든 테이블 행(합성 예약/승인 결제/상태 이력/V36 사건 포함) 및 기존 Flyway history/checksum byte snapshot을 비교하고 validate했다. 운영자 SQL도 실제 PostgreSQL에서 구문·범위·제한을 검증했다.
- 선기록 실패 무전송, 결과 저장 실패 UNKNOWN/no-replay, 실제 commit 뒤 Java 경계 예외, 부분 fanout, 중복 claim/mark, 살아 있는 stale token/늦은 결과, 설정·기한·상태·연결 세대, HTTP503/SSE payload 계약을 검증했다. Java 경계 예외는 실제 DB 프로토콜 응답 유실 시험이 아니다.

원시 자료는 `build/sse-l-20261111/`에 고유 실행명으로 보존했다. 실패/성공 XML·로그·exit code, 마이그레이션 지문, 소스 delta·복구 전 파일 묶음은 관리자 공유 evidence에 별도로 보존한다. 최종 일부 bootJar 작업은 main 소스가 같아 UP-TO-DATE였으며, 앞선 실제 bootJar 실행 성공과 artifact 지문을 근거로 삼는다.

## 운영·후속

[동작·설정·전환/rollback 문서](../docs/waiting-sse-durability.md), [제한 timeline SQL](../scripts/waiting-sse-journal.sql), [health SQL](../scripts/waiting-sse-health.sql)을 함께 제공한다. DB/디스크 소실, 실제 JVM 종료·DB COMMIT 응답 유실·실제 SSE 연결 성능, 최종 I 1083+ 전체 회귀는 후속 관리자 배정 범위다. 실제 배포 토폴로지/권한/보존/용량 결정도 남아 있다. 해당 단계를 끝낸 것으로 보고하지 않는다.

별도 앱/fake 서버를 띄우지 않았고 테스트가 사용한 임시 서버·JDBC pool·Gradle JVM은 종료됐다. 전용 PostgreSQL 컨테이너/DB와 원시 자료는 보존하고 실행 슬롯을 반환한다. 현재 대화 워크트리는 통합/독립 검토 전 상태로 보존한다.

## 2026-11-11 독립 리뷰 후 보완

초기 구현·위 검증 기록과 고정 증거를 보존하고 다음 세 항목을 보완했다. 관련 전체 수정본을 다시 실행했으며 이전 371개 통과를 새 수정본의 검증으로 대신하지 않았다.

- 제한 운영 SQL은 root 전체 열과 target 원문 message 접근이 필요해 문서의 최소 권한에서 실패했다. 전용 합성 DB의 별도 비소유자 LOGIN으로 기존 SQL의 42501 오류를 먼저 재현했다. V38을 바꾸지 않고 V39 owner-rights projection view를 추가하고, SQL을 필요한 열과 eventId만 읽도록 변경했다. 새 별도 LOGIN에서는 timeline·health 성공, 원문/업무 details 조회와 원본·view 쓰기 8건 거절을 확인했다. view는 전체 원장 관찰이 승인된 운영자용이고 매장별 RLS가 아니며 실제 운영 역할·권한을 변경하지 않았다.
- `WaitingSseJournalRepository`로 SQL·행 매핑을 분리했다. 정책·payload·TX 조율은 Journal에 남겼으며 공개 단계/record, commit 반환·send 관찰 경계와 잠금 순서를 유지했다. 같은 업무 TX 원자성, 결과 저장 재시도/no-replay, 작은 DB 풀 검증을 포함한 기존 회귀를 그대로 통과했다.
- 중복 PENDING 루프를 시험 전용 `WaitingSseTestDrain`으로 모았다. fixture 범위의 DONE 또는 호출부가 명시한 root별 잔존 상태를 검사하며 제한 소진·CLAIMED·예기치 않은 UNKNOWN을 실패시킨다. 정상 DONE은 확장 수와 미완료 대상 부재까지 단언한다. TX 내부는 worker 없이 선기록 상태를 검사하고 rollback 뒤 root 부재·무전송을 확인한다.

보완 핵심 회귀 29개 통과 후 첫 관련 전체 377개 중 1개가 실패했다. 새 helper가 기존 컨트롤러 rollback 테스트의 TX 내부 drain 호출을 검출했으며, 해당 검사를 명시적 PENDING/무전송·rollback 후 root 부재 검사로 수정했다. 최종 **33클래스 377개, 실패/오류/skip 0**을 실제 재실행했다. payload·횟수·상태 assertion은 유지했고 추가 6개는 helper 4개, V39 마이그레이션 1개, 실제 권한 1개다.

fresh V38·V39, V37→V38·V38→V39를 검증했다. 업그레이드 전 모든 테이블 행(예약·결제·V36 사건·V38 UNKNOWN 시도/관찰 포함)과 이전 Flyway history/checksum snapshot이 유지된다. V1~V38 실제 37개 파일의 SHA256도 초기 고정본과 동일하다. bootJar는 첫 전체 실행에서 실제 생성됐으며 최종 실행에서는 main 불변으로 UP-TO-DATE였다. 현재 main class/resource와 artifact를 바이트 대조하고 시험 helper/권한 시험/과거 관찰 fixture가 운영 JAR에 없음을 확인했다.

후속 원시는 `build/sse-review-fixes-20261111/`, 공유 증거는 `COORD/evidence/L-sse-review-fixes/`에 따로 보존한다. 초기 555파일/191개 증거·고정 입력을 덮어쓰지 않으며 555 대비 후속 delta와 원래 542 대비 누적 delta/복구 묶음을 제공한다. 전체 I 및 실제 JVM/DB 프로토콜 장애·연결·성능 F 실행은 아직 후속 단계다. 자기 시험 JVM·fake/JDBC/Gradle은 종료하고 전용 DB와 자료는 보존해 슬롯을 반환한다.
