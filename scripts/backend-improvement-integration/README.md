# 합본 로컬 정확성 검증

2026-11-13 안내. 이 폴더의 `verify.py`는 2026-11-11 통합 고정본(V37까지)을 검증할 때 쓴 과거 도구다. 아래는 당시 기록 원문이다. 본문이 "현행 V38/V39"나 `verify_sse.py`라고 부르는 웨이팅 SSE 영속화 구현과 도구는 이 시점의 저장소에 없다. 지금 소스는 `verify.py`의 고정 지문과 달라 실행 전에 거절된다. 현재 범위와 검증은 [분리 검증 기록](../../work-log/2026-11-13-pg-call-isolation-pr-split.md)을 따른다.

## 과거 V37 고정본 전용 — 아래 verify.py 명령은 역사

아래 `verify.py`는 2026-11-11 A~D 재통합 고정본의 V35→V37와 PG restart 전용이다. 현행 V39에서 사용하지 않는다. 기존 이력34행→36행, 신규 V36/V37라는 과거 단언을 유지한다. 숫자만38로 바꿔 현행 검증으로 취급하지 않는다.

2026-11-12 안전 보완으로 **출력 생성·probe import·build·DB 접근 전에** `src/main`325개 전체의 canonical path/SHA map을 검사한다. 고정 지문은 `3153d3edebb7b4cf3f2049d4ef7b0d598fc01eb3299d94a82e3930ac4ba5ef36`이며 symlink나 현재 V38/V39를 포함한 다른 제품 입력이면 종료2로 거절한다. 현재 소스의 migration을 지워 우회하지 않는다. 고정본은 보존 `final-reintegration-20261111/integration-delta/source-sha256.json`과 같은 제품 소스, 기준 커밋 및 해당 delta 묶음으로 복원한 **별도 디렉터리**여야 한다. 현재 checkout을 덮어쓰지 않는다. 보존본에 이번 guarded `verify.py`를 사용한 뒤 다음 파일 검사만 먼저 할 수 있다.

```sh
# 복원한 V37 고정본 루트에서 실행. output은 검사만 하며 만들지 않는다.
python3 scripts/backend-improvement-integration/verify.py --mode upgrade --check-only \
  --output build/backend-improvement-integration/historical-preflight-unused
```

성공 표시는 `VALID_HISTORICAL_V37_INPUT`이고 앱/DB 검증 성공이 아니다. 고정본이 없거나 지문이 맞지 않으면 보존본 소유자에게 재현 묶음을 받아야 한다. `verify_sse.py`는 이 옛 도구를 실행하지 않고 합성 seed SQL 문자열만 AST로 읽으므로 guard가 현행 upgrade를 막지 않는다.

다음 명령은 과거 고정본·전용 자원 배정이 있을 때만 사용하는 재현 형식이다. 성능 비교는 실행하지 않는다.
`ct-improve-i-20261111` 소유 label과 로컬 이미지/포트 바인딩을 확인한 후
`127.0.0.1:55435/ct_improve_i`, 사용자 `catchtable` 및 일회용 로컬 테스트 값만 사용한다.
실제 비밀 설정이나 `.env`를 읽지 않는다. 앱18085/fake19085가 사용 중이면 중단한다.

```sh
# 기존 로컬 Java21, Gradle 캐시, postgres:16 이미지만 사용
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m'
python3 scripts/backend-improvement-integration/verify.py --mode upgrade --output build/backend-improvement-integration/03-upgrade
python3 scripts/backend-improvement-integration/verify.py --mode restart --output build/backend-improvement-integration/04-restart
```

출력 디렉터리와 대상 스키마(`i_upgrade_v35`, `i_restart`)가 이미 있으면 보존을 위해 중단한다.
재실행이 필요하면 이전 증거·스키마를 보존하고 `--schema i_upgrade_v35_v2`처럼 새 소유 스키마를 지정한다.
전체 JUnit의 context cache 상한4는 `src/test/resources/spring.properties`로 적용된다. 운영 JAR에는 포함되지 않는다.
공통 D 도구는 `PAYMENT_PROBE_*` 변수로 DB/컨테이너/포트/스키마를 격리하며,
기본값은 원래 D 환경이다. 통합 도구가 모든 변수를 명시한다. Gradle은 항상 `--offline --no-daemon --max-workers=2 -Dorg.gradle.java.installations.auto-download=false`를 사용한다.

- upgrade: 실제 Flyway target35 적용 후 합성 예약100·결제100, 기존 웨이팅/종료/연기/알림 outbox/결제상태 이력/환불/V33 outbox를 넣는다. V36+V37 적용과 앱 재기동의 Flyway·JPA validate, 전체 기존 테이블 내용과 기존 이력34행 동일, 새 이력 backfill0을 단언한다. 전후 JSON·SQL dump·실행SQL·앱 명령/로그를 보존한다.
- restart: 실제 Boot 앱 배선에서 PG8초 지연 중 공통 scheduler의 예약·웨이팅 만료와 C의 AUTO_EXPIRED 이력 연결을 확인한다. 실제 jcmd 스레드 덤프로 PG worker 전용 실행을 확인하고, 승인 응답 유실·durable job 처리 중 kill/restart 및 환불 UNKNOWN 후 kill/restart를 수행한다. 승인/환불 원장 효과 각1회, 기존3000원/잔액7000원, PARTIAL_REFUNDED와 job DONE을 단언한다.
- restart에서는 관찰 시간을 확보하려고 로컬 smoke에만 read10s/call11s를 사용한다. 제품 기본 timeout과 비교 측정 조건은 변경하지 않는다. lease는 SQL로 가속하며 실제120초 대기는 하지 않는다. 정확한119/120초 및 살아 있는 stale worker 경합은 JUnit 증거와 구별한다.

프로세스는 생성한 자식 PID만 종료한다. DB·스키마·원시 자료는 보존한다. 일반 앱의 공유 Hikari/CPU까지 분리됐다는 주장이나 실제 공급사·배포 검증으로 확대하지 않는다.

### I-SPEC-1 후속 — 작성 완료, 실행 배정 대기

이전 `04-restart`는 PG 대기/만료/C 이력 결과를 관측했지만, fixture 이전 HTTP 진입과
관찰 종료까지 같은 요청의 대기를 자동 단언하지 않았다. 추가본은 공통 smoke의 요청 ID 및
응답 시작/종료 marker를 이용해 그 순서를 검사한다. `verify.py`의 C 이력·scheduler observer를
전후 요청 snapshot으로 감싸며, 결과는 `scheduler-overlap.json`과 `smoke.json`에 남긴다.

C 본 측정 종료 후 관리자가 해당 슬롯을 배정하면 **restart만** 새 스키마·출력 경로에서 실행한다.
아래는 예약된 후속 명령이며 이번 작성 단계에서는 실행하지 않았다. 기존 출력/스키마는 보존한다.

```sh
python3 scripts/backend-improvement-integration/verify.py --mode restart \
  --schema i_restart_spec1 --output build/backend-improvement-integration/07-restart-spec1
```

기존 delay8초/read10초/call11초, JVM kill/wait·복구/효과1회 단언을 유지했다.
제품 src/비교 측정 조건은 바꾸지 않았으므로 전체회귀·upgrade를 불필요하게 반복하지 않는다.

2026-11-11 후속: 위 실행 대기는 종료됐다. 새 `i_restart_spec1`/`07-restart-spec1`에서 동일 GET의 진입→fixture→만료/observer 완료 중 미응답→kill 및 재시작을 실제1회 검증해 통과했다. 자세한 결과와 남은 범위는 [통합 기록](../../work-log/2026-11-11-backend-improvement-integration.md)의 마지막 절을 따른다.

### 2026-11-11 최종 C/D 재통합 검증

최종 C29/D32 고정본을 적용한 뒤 새 `i_final_build`에서 전체 build의 99클래스/1,083 tests가
실제 실행되어 실패·오류·skip0으로 통과했다. `--rerun-tasks --no-build-cache`로 결과 재사용을 막았고,
새 recorder6개와 DB 이력2개도 포함됐다. test-only cache4는 그대로다.

```sh
# 이미 완료한 실행의 재현 형식이다. 기존 출력/스키마는 재사용하지 않는다.
python3 scripts/backend-improvement-integration/verify.py --mode restart \
  --schema i_restart_final_batch --output build/backend-improvement-integration/08-final-batch
```

이 실행은 동일 PG GET ID1이 fixture 전과 만료/C 이력·thread 관찰 후까지 미응답임을 단언한 뒤
실제 JVM을 중단·재시작하여 승인/환불 효과 각1회와 원래 환불 의도/상태를 확인했다.
전체 명령·환경·원본 XML·소스 지문·복구 자료는 `build/backend-improvement-integration/final-reintegration-20261111/`와
관리자 COORD의 `evidence/I-final-reintegration-20261111/`에 보존한다.
기존 upgrade 증거는 SQL 동일성을 대조해 재사용했으며 성능 비교는 재실행하지 않았다.
