# 백엔드 개선 A~D 통합 검증 — 2026-11-11

## 범위와 입력

기준 HEAD `a44952e37993e5003c860714f126057cd311dba5`, 브랜치 `test/backend-improvement-integration`에서 고정 입력 `20261111-post-b`를 적용했다. A10/B11/C24/D32, 총77개 파일이며 교집합은 없다. manifest SHA256은 `468b0a7d656dec671dce7609c25212ee255819c0be90000367c696cf7c07e1dd`다. 각 patch/file hash와 현재 HEAD/clean 상태, git apply --check를 확인한 뒤 patch와 untracked 파일의 모드를 보존해 적용했고 77개 결과 hash가 모두 일치했다.

입력 원본·다른 작업트리·관리 공간은 수정하지 않았다. 외부 조회, fetch/pull/push, 커밋·PR·merge·배포·실제 Toss/SOLAPI·외부 의존성 다운로드는 수행하지 않는다. 이번 범위는 합본 정확성 검증과 필요한 보완이며 C/D 본 성능 비교 및 관리자 사용자 결정 감사는 후속이다.

## 환경

기존 로컬 Java21.0.12.1과 Gradle 캐시, `postgres:16` 이미지 `1a6ab3f5345e`를 사용한다. 새 `ct-improve-i-20261111` 컨테이너를 `--pull=never`, loopback55435, DB `ct_improve_i`, 사용자 `catchtable`로 생성했다. 비밀번호는 전용 일회용 로컬 테스트 값이다. 다른55431~55434 DB와 axum5375는 연결하지 않았다.

Gradle은 `--offline --no-daemon --max-workers=2 --console=plain`, JVM `-XX:ActiveProcessorCount=2 -Xmx512m`을 사용한다. 테스트 외부URL은 loopback19085/19084이며 SOLAPI는 기본 비활성 또는 테스트 내부 loopback fake다. 일반 앱18085/fake19085 및 D 통합 테스트 고정fake19084의 기존 리스너가 없음을 확인했다. 실제 비밀 설정을 읽거나 복사하지 않았다.

정확한 환경/명령/시간/소스 hash와 원본 로그/XML/SQL은 `build/backend-improvement-integration/`에 보존한다. 다음 clean에 없어질 수 있으므로 관리자 COORD evidence에도 최종 복사한다.

## 첫 전체 실행 실패와 최소 보완

`01-full/`: build exit1, 98클래스1075개 중 실패89, 오류0, skip0. `UserWaitingControllerTest`부터 여러 Spring test context의 Hikari 연결 풀이 누적되어 PostgreSQL 기본 max_connections에 도달했다. 첫 원인은 Flyway context 초기화의 `FATAL: sorry, too many clients already`; 같은 실패 context의 후속 테스트는 Spring failure threshold 오류였다. PostgreSQL 로그와 원본 XML을 보존했다. 종료 후 DB 연결은 조회 연결1개뿐이었다.

제품/기대값/skip 변경 대신 `JAVA_TOOL_OPTIONS`에 `-Dspring.test.context.cache.maxSize=4`를 추가해 오래된 테스트 context와 그 연결풀이 닫히도록 제한했다. 같은 전체 build를 재실행한다. 운영 풀이나 PostgreSQL max_connections를 바꾸지 않았다.

## 추가 도구 보완

- D `scripts/payment-isolation/run.py`의 DB/컨테이너/스키마/앱·fake 포트를 명시적 환경변수로 분리하고 offline Gradle을 적용했다. D 기본값은 보존했다. fake 서버에 지정 포트를 전달한다.
- 테스트 probe는 scheduler bean/pool/prefix와 실제 공통 heartbeat thread를 로그에 남긴다. 운영 bootJar에는 포함되지 않는다.
- [통합 정확성 도구](../scripts/backend-improvement-integration/README.md)는 I 컨테이너 소유 label·이미지·loopback binding과 스키마 미존재를 검사하고 실제 V35→V37 upgrade 및 JVM kill/restart를 검증한다. C 만료 이력의 상태/원본종료이력/시각과 PG 지연 중 실제 전용 thread를 함께 확인한다.
- 입력 대비 수정 diff/이유/새 hash는 최종 증거 `integration-delta`에 별도 남긴다. 제품 Java/기존 migration은 현재 변경하지 않았다.

## 기존 SQL과 PR270

기존34개 SQL은 기준 HEAD와 바이트 동일하다. V36·V37만 추가되며 V38은 없다. 로컬 origin/dev는 기준 HEAD와 동일하다.

PR270 head `6979a9b0c08a906c047885b910a86d9a49dffd94`는 로컬 object에 없다. 기존 COORD reports/B.md 검토 근거에 따르면 PR은 `Collection` import와 `findAllByStoreIdInAndBusinessDateBetween(Collection<Long>,LocalDate,LocalDate)`를, B는 `findAllByStoreIdAndBusinessDateBetween(Long,LocalDate,LocalDate)`를 같은 위치에 추가한다. 시그니처는 다르므로 두 메서드/import를 보존할 수 있으나 텍스트 삽입 충돌 가능성이 있다. PR은 포함하지 않았으며 외부 최신 상태는 미확인이다.

## 최종 보완과 전체 결과

환경 플래그만으로 남기지 않고 `src/test/resources/spring.properties`에 `spring.test.context.cache.maxSize=4`를 추가했다. 로컬 Spring6.1.13 캐시 API가 SpringProperties를 읽는 것을 기존 JAR의 javap로 확인했다. 운영 설정/DB max_connections는 그대로이며 테스트 전용 context 수명만 제한한다. 최종 실행에서는 JVM의 cache 플래그를 제거하고 이 파일만으로 검증했다.

| 단계 | 실행 결과 | 증거 |
| --- | --- | --- |
| 최초 전체 build | 98클래스1075개, 실패89/오류0/skip0, exit1; 연결 누적 환경 실패 | `01-full/` |
| 환경 cache상한4 전체 build | 98클래스1075개, 실패0/오류0/skip0, exit0 | `02-full-cache-bounded/` |
| 테스트 resource 반영 후 최종 build | **98클래스1075개, 실패0/오류0/skip0, exit0** | `05-full-test-config/` |
| V35→V37 실제 upgrade/앱 재기동 validate | 기존18테이블 내용 및 기존Flyway34행 동일, 최종36행, 새 waiting_event/job0 | `03-upgrade-v2/` |
| 통합 앱 JVM kill/restart | 승인·환불 복구 및 scheduler/C이력 통과 | `04-restart/` |

최종 명령은 전용 DB/loopback URL 환경을 주입한 `./gradlew build --offline --no-daemon --max-workers=2 --console=plain`이다. `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m'`. 세 전체 실행은 반복 증거이므로 테스트 수를 합산하지 않는다. 첫 실패, 환경 원인 검증, 공통 테스트 설정 변경 때문에 각각 필요했고 최종 통과 후 추가 전체/표적 반복은 하지 않았다. test task는 실제 실행됐고 XML 개수·개별 testcase를 직접 집계했다. 패키징 성공 및 V36/V37 포함, probe/test spring.properties가 운영 bootJar에 없는 것도 확인했다.

D `PaymentStaleWorkerIntegrationTest`는 SHA256 `6a6cf6c9291bad0c8c1824398a09f7ccc2124c63efb45f0d99d6c1e0b45fcdb1` 그대로 2개 테스트가 최종 전체에 포함되어 통과했다. 첫 경합 PARTIAL_REFUNDED, 둘째 job DONE의 새 두 상태 단언이 실행됐다. 이전 D273개 성공을 대체 근거로 사용하지 않는다. B `BusinessHourWaitingConflictTest`8건과 C `WaitingServiceTest`28건도 모두 통과하여 기존 자정 fixture2건이 합본에서는 해결됨을 확인했다.

## 요구별 실행 근거

- A: 가용수량 batch19·기존조회2·시간2·예약설정42·별도연결 생성경합6. 단건/batch 의미·0수량/순서/인원·자정/전날/특정일/휴무/브레이크/마감·선점만료 전/정각/후·승인진행/UNKNOWN 보호·격리, 기존 생성 잠금과 PostgreSQL 제약을 검증했다.
- B: snapshot9·경합12·활성웨이팅 충돌8·요일5·특정일13. 실제 별도 DB PID/잠금 대기 관찰 후 게이트를 열어 요일교체/특정일설정·삭제와 예약생성의 선행 커밋·롤백 조합을 검증했다. 잠금을 옮기거나 API/정책을 변경하지 않았다.
- C: 새 통합이력18·기존데이터 migration1·실제 loopback SOLAPI HTTP흐름1 및 웨이팅/알림 전체. 같은 트랜잭션 상태/이력/outbox 롤백, 같은 사건 유일키/중복listener, 정당한 반복호출 차수, 최초 기록 유지, SSE다중/없음/끊김/포화/저장실패, 공급사 ACCEPTED/SENT와 고객수신 UNKNOWN, 두 ID/500상한/민감본문 제외/출처구분 SQL을 확인했다. 전체 testcase 목록은 `coverage.json`과 최종 XML이다.
- D: 실제 fakeHTTP 격리16·살아 있는 stale worker2, 멱등경합4·재확인경합3·예약결제46·V33outbox20·실제 scheduled1 및 결제 전체. 승인 동기/전송 전503·완료replay·UNKNOWN보호·응답유실/한도·webhook durable중복/저장실패·lease119/120초·retry/version fencing·V33환불 원래의도/키/금액/15일 경계와 별도 DB NOWAIT를 검증했다. HTTP 시도 수와 원장 효과를 구분한다.

위 수는 전체1075에 포함되는 관련 부분집합이며 별도 가산하지 않는다. A/B/C 제품 소스 및 D 제품 소스는 입력 이후 그대로여서 A/B 기존 성능 측정 대상의 제품 변경은 없다. 새 성능 수치는 만들지 않았다.

## Upgrade 및 도구 초기 오류

최초 upgrade 도구는 기본값과 override를 CLI에 중복 전달해 Spring이 `100,3600000`을 fixedDelay로 해석하며 시작에 실패했다. Flyway V35까지는 적용됐고 JPA initialize 뒤 scheduling 생성에서 실패했다. 제품 결함이 아닌 통합 도구 조립 오류다. 중복 인자의 기본값을 대체하도록 수정하고 조기 프로세스 종료를 즉시 보고하게 했다. 실패 스키마 `i_upgrade_v35`와 로그 `03-upgrade/`를 보존하고 새 `i_upgrade_v35_v2`에서 재검증했다.

성공 upgrade는 실제 Flyway target35로34개 SQL을 적용한 뒤 합성 예약100·결제100, 웨이팅/종료/연기/알림 outbox/환불요청·환불/V33outbox/결제상태이력을 넣고 V36·V37을 적용했다. 모든 기존18테이블의 JSON내용과 Flyway34행 전체가 동일하다. V36 과거 backfill0, V37 job0, 최종36개 이력 모두 성공이다. 앱 재기동 Flyway checksum validation 및 JPA ddl-auto validate를 확인했다. 전후 dump와 실제 SQL을 보존했다.

## 실제 scheduler와 프로세스 장애

전체 Boot 앱에서 `taskScheduler`/`paymentTaskScheduler`가 각각 존재하고 공통 heartbeat는 `scheduling-1`, 실제 PG 재확인 대기는 `payment-worker-1`에서 실행됨을 jcmd thread dump로 확인했다. PG8초 지연 중 예약101이 EXPIRED, 웨이팅1이 NO_SHOW/AUTO_EXPIRED가 됐고 C 이벤트는 CALLED→NO_SHOW, source=WAITING_END_HISTORY, 원본 이력ID/시각 및 waiting.endedAt과 일치했다. 같은 트랜잭션 롤백 보장은 위 JUnit18개로 함께 뒷받침된다. 독립 DB 연결 NOWAIT도 성공했다.

실제 PID72384를 작업 PROCESSING 중 kill하고 PID72513으로 재시작해 DONE/CONFIRMED를 복구했다. 이후 환불 응답유실 UNKNOWN에서 다시 kill하고 PID72559로 재시작해 PARTIAL_REFUNDED/CONFIRMED, 환불·예약환불요청 COMPLETED, job DONE을 확인했다. fake PG 총 HTTP5회와 외부 원장 효과 승인1/환불1을 분리 기록했다. 최초 승인키와 `probe-refund-original`, 환불의도 PAYMENT_ONLY·3000원·잔액7000원을 유지했다. 첫 crash후 lease만 SQL로 만료시켰으며 정확한119/120초 경계와 동시 stale-worker는 JUnit이다.

probe Clock은 각 JVM 시작을2030-01-20 12:00 KST에 맞추고 경과 시간으로 흐른다. 재시작 사이 실제 wall-clock 연속성을 검증한 것은 아니며 그 결과 일부 합성 이력 시각 순서는 역전될 수 있다. smoke read10s/call11s는 PG대기 중 관찰을 위한 로컬 설정이며 제품 timeout 기본값·성능 비교 조건을 바꾸지 않았다. 실제PG·다중앱 배포 검증으로 확대하지 않는다.

## 보존 상태와 남은 범위

최종 검사에서 Gradle/앱/fake Java와 18085/19085/19084 리스너가 없고 DB에는 확인용psql 외 앱 연결이 없다. 전용 컨테이너는 실행 상태로 보존했다. public(V37), 실패 i_upgrade_v35(V35), 성공 i_upgrade_v35_v2(V37), i_restart(V37)와 전체DB SQL dump를 남겼다. 워크트리/원본 A~D/merganser/rudderfish 및 기타 DB·사용자 앱은 정리하지 않았다. 커밋·게시·backport 없음.

`git diff --check`, Python 구문, 입력 관련문서+추가문서 상대 링크47개, 명령/DB/포트/마이그레이션 설정 일치를 확인했다. 입력대비 보완은 테스트설정1·기존D도구4·새 통합도구2·작업기록1, 총8파일이다. 최종81개 변경 파일 전체/입력77개/추가수정8개의 경로·모드·hash 및 별도diff를 `integration-delta/`에 보존하고 관리자가 원 담당자에게 전달할 수 있게 했다.

이번 I 정확성 재검증·보완은 완료했다. C/D 본 성능 비교, 전체업무1·2 종료 후 관리자의 사용자 의사결정 점검은 남는다. C의 HTTP재시도 식별·관찰큐 유실·고객ACK UNKNOWN/운영자SQL 권한, D의 프로세스별 cap·503재시도·공유자원/무서명웹훅 계약은 기존 문서의 한계/정책 후보로 유지했다. 새로운 멱등키 API·전달구조·실PG·운영한도·서명인증·배포 정책은 대신 정하지 않았다. CI·프런트·실제PG/SOLAPI·공유DB·배포는 미검증이다.

## 2026-11-11 I-SPEC-1 후속 작성 — 실행 대기

Task `task_58583190102f` / dispatch `ctx_e66ddda34729`의 허용 범위는 도구/문서 보완 및 정적 검사뿐이다. C 본 성능 측정이 독점 실행 슬롯을 쓰므로 이번에는 Gradle·앱·fake PG·DB·부하를 실행하거나 상태 조회하지 않았다. 기존 I의 385개 보존 증거와81개 파일의 지문을 수정 전에 재확인하고, 기존 결과를 추가본 통과 증거로 사용하지 않는다.

Spec의 P3 I-SPEC-1은 제품 결함이 아니라 검증 시간순서 공백이다. 기존 smoke는 job PROCESSING 후 만료 fixture를 넣고 만료 후에만 PG active를 보아, 해당 HTTP가 만료 이전에 시작됐다고 자동 단언하지 못했다. 위 완료 기록은 수정 전 실행의 역사로 보존하되 엄격한 시간 겹침에 관한 이 한계를 추가한다.

`fake_pg.py`에 smoke opt-in 관찰을 넣었다. 지정한 GET `/v1/payments/probe-key-1`의 단조 증가 요청ID, method/path, 진입 시각, active, 응답 시작/종료 marker를 서버가 기록한다. `run.py` smoke는 해당 HTTP 진입을 기다리고 같은 ID가 응답 전임을 확인한 뒤 fixture를 넣는다. 만료 후 및 C 이력·scheduler observer가 끝난 후에도 같은 ID·진입 시각·method/path, active와 미응답 상태를 단언한다. 중간에 응답이 시작되거나 요청이 교체되면 통과하지 않는다. natural delay를 늘리거나 새 gate를 추가하지 않았다.

후속 실행에서 `scheduler-overlap.json`에 `pg-entered-before-fixture → fixture-insert-start → fixture-inserted → expiry-observed → observer-complete-before-kill → overlap-verified → jvm-killed` 순서, runner 단조시각, 같은 서버 요청의 전후 snapshot을 남기도록 했다. 관찰 완료 전에는 overlap_verified=false다. 서버/runner 시각을 서로 빼서 지연을 추정하지 않으며, runner 순서와 서버의 동일 요청 미응답 marker로 범위를 증명한다. 프로세스 kill/wait·PROCESSING·lease SQL가속·재시작과 기존 승인/환불 효과1회/최종 상태 단언은 보존했다.

정적 검사: Python AST/compile 구문, smoke 이외 runner 함수 및 compare 분기 동일성, 기존 상태/원장 단언과 stop 호출 보존, src 전체 SHA256 불변, 문서 링크/명령 및 git diff --check를 확인한다. 이는 코드 검사이고 실제 HTTP/스케줄러 동작 통과가 아니다. 비교모드는 관찰을 켜지 않으며 원래 delay/timeout·요청·워밍업·표본 조건은 그대로다.

기존 `evidence/I-20261111-integration/`의385파일/manifest/입력은 덮어쓰지 않는다. 추가 delta와 정적검사·전후 소스는 별도 `build/backend-improvement-integration/spec1-preparation/` 및 COORD `evidence/I-spec1-preparation-20261111/`에 보존한다. 이번 변경은 도구2·README2·본기록1과 관리자보고서 후속기록이며 제품 src/verify.py/기존 테스트 설정은 변경하지 않는다.

**남은 실행:** C 본 측정 종료와 새 슬롯 배정 후 새 `i_restart_spec1`/`07-restart-spec1`에서 restart/scheduler 검증만 실행한다. 전체회귀·upgrade·제품 보완은 새 실패/영향 없이는 반복하지 않는다. 추가본의 실제 겹침 검증은 현재 대기이고 사용자 감사는 전체1·2 이후다. 커밋·외부조회·게시 없음.


## 2026-11-11 I-SPEC-1 실제 재검증 완료 — 슬롯 반환

Task `task_89da5fd61004` / dispatch `ctx_4d9a9cb86007`의 독점 정확성 슬롯에서 준비본을 새 `i_restart_spec1` 스키마 / `build/backend-improvement-integration/07-restart-spec1`에 실행했다. 04:10:00~04:10:26 KST, exit0, 도구 오류/코드수정/재시도 없이 1회 통과했다. 실행 소스438개(src432+도구6) hash가 전후 동일했다. JUnit 전체회귀/upgrade 모드/성능 비교는 실행하지 않았고 필요한 offline probeClasspath 준비와 restart/scheduler만 수행했다.

원본 `scheduler-overlap.json`과 `smoke.json`에 실제 GET `/v1/payments/probe-key-1`, request_id1의 진입 확인→fixture 시작(0.334ms)/완료(47.085ms)→만료 확인(133.001ms)→C 이력/thread observer 완료(448.459ms)→실제 kill(468.128ms) 순서가 남았다. 수치는 첫 진입 확인 표본 이후 runner 내 상대시각이다. 진입/만료/observer 완료의3개 표본 모두 같은 서버ID/진입시각, active=true, response_started_ns/finished_ns=null이었다. 따라서 다른 요청의 active가 아니라 동일 GET의 미응답 구간 안에서 fixture·만료·C이력/scheduler 관찰이 수행됐음을 자동 단언했다. 서버/runner 절대단조시각을 서로 빼지 않았고 성능지표로 사용하지 않는다.

실제 PID93928 kill→94080 승인복구→환불 UNKNOWN 후 kill→94112 복구를 확인했다. 최종 PARTIAL_REFUNDED/CONFIRMED, job DONE, refund/request COMPLETED, 최초 PAYMENT_ONLY/3000원/키 probe-refund-original/잔액7000, fake HTTP5회와 승인효과1/환불효과1을 구분해 확인했다. `scheduler-history.json`의 CALLED→NO_SHOW/AUTO_EXPIRED·원본 이력/시각 일치와 thread dump의 공통/PG 분리도 통과했다. SQL lease 가속·JVM별합성Clock·실PG 미검증 한계는 이전과 같다.

04:11:12 KST 종료 확인: Java/Gradle0,18085/19085/19084 listener0, 자기DB의 검사psql 외 연결0. 컨테이너와 신규 스키마/최종dump는 보존했다. 원시 로그·overlap·SQL·명령·시작/종료hash·프로세스/포트 상태는 위 출력 및 COORD `evidence/I-spec1-runtime-20261111/`에 별도 보존한다. 기존385파일과20파일 준비 증거를 덮어쓰지 않았다. I-SPEC-1 런타임 공백은 해소됐으며 C의 후속 관찰누락 보완 소스는 이 실행에 포함하지 않았다. 슬롯을 반환하고 C 보완 회귀/재측정 및 후속성능·사용자감사는 관리자 순서에 남긴다.


## 2026-11-11 최종 C29/D32 재통합 — 전체 build와 강화 restart 통과

Task `task_1d06baa84fcb`, dispatch `ctx_d81fd95d3af2`. 기존 I의81개 변경과 이전 증거385+20+38파일을
복구 tar/해시로 먼저 보존했다. A10/B11은 그대로이며 최종 C29/D32 입력으로 총82개를 대조했다.
C는 기존24개 중8개와 신규5개만 반영했다(제품 recorder/repository2개, 테스트2개 포함).
D 실행 도구3개는 I와 일치했고 README/work-log만 최신화했다. D README에는 I의 격리 변수·통합 링크·
I-SPEC-1 설명을 유지했다. 추가 소스 변경 없이 probe build의 자동 Java 다운로드 금지 인수만 추가했다.

새 `i_final_build`에서 05:02:26–05:03:26 KST에 전체 build를 실행했다.
명령은 `./gradlew build --rerun-tasks --no-build-cache --offline --no-daemon --max-workers=2 -Dorg.gradle.java.installations.auto-download=false --console=plain`이다.
Java21.0.12.1/전용DB55435/로컬 fake URL/알림 비활성 설정과 test-only cache4를 유지했다.
실제 XML **99클래스 1,083 tests / failures0 / errors0 / skipped0**, Gradle8작업 모두 실행이다.
새 recorder6개, WaitingEventHistory20개(새2개), PaymentIsolation16개/StaleWorker2개 및 A/B·웨이팅 회귀가 포함됐다.
이번 실행 실패는 없으며 JVM CDS와 Gradle10 호환성 deprecation 안내는 원본 로그에 남겼다.

새 `i_restart_final_batch`/`08-final-batch`에서 05:03:51–05:04:15 KST 강화 restart를1회 통과했다.
같은 GET `/v1/payments/probe-key-1` ID1의 진입 확인을0ms로 하여 fixture시작0.242ms, 투입44.959ms,
만료309.169ms, observer완료621.080ms, 검증621.318ms, 실제kill640.826ms였다.
진입/만료/observer종료 snapshot 모두 같은 entered_ns, active=true, response_started/finished=null이었다.
PG worker는 `payment-worker-1`, 공통은 `scheduling-1`이며 예약EXPIRED/웨이팅NO_SHOW와
C AUTO_EXPIRED 이력의 source ID·시각/종료시각 일치를 같은 상태 변화에서 확인했다.
JVM PID27806→27920→27948의 실제 중단·재시작, 별도 연결 NOWAIT 성공을 보존했다.
최종 PARTIAL_REFUNDED/CONFIRMED/jobDONE, 환불요청COMPLETED, 최초 PAYMENT_ONLY/3000원/
`probe-refund-original`/잔액7000원과 fake 승인·환불 효과 각1회다(HTTP시도5회와 구분).
lease는 SQL 가속이고 probe Clock은 JVM마다 초기화되므로 실제120초/연속 wall clock이나 실제 PG를 입증하지 않는다.

새 두 스키마는 각각 V37/36개 이력 성공이고 Flyway/JPA validate를 확인했다.
기존34개 SQL은 HEAD와 바이트 동일하며 V36/V37도 이전 검증본과 같다. V38은 없다.
따라서 이전 V35→V37의 기존자료 보존/무backfill 증거를 연결하고 upgrade는 반복하지 않았다.
기존5스키마의 데이터/COPY·시퀀스182항목은 전후 동일했다. 새/기존DB와 원시 증거는 보존했다.
05:05:06 KST 앱18085/fake19085/19084 listener0, 앱·fake·Gradle0, 확인SQL 외 DB세션0이다.

C19개/D22개 src 입력을 각 실제 성능 실행의 저장된 지문과 대조했고, A/B 입력도 그대로다.
A/B/C/D 부하를 재실행하지 않았다. C batch의 표본관찰31,200행/전체35,400행 무누락은 C 단독 조건이며,
유한 queue512의 일반 무손실 보장은 아니다. D48,000건은 D 단독 조회/웹훅 접수이고 합본 처리량이나
승인·환불 처리량 증거가 아니다. 원래 손실·timeout60건·클라이언트 시작 지연 한계도 보존한다.

원본은 `build/backend-improvement-integration/final-reintegration-20261111/`, `08-final-batch/`;
영구 보존은 COORD `evidence/I-final-reintegration-20261111/`다. 별도 입력→최종/이전I→최종 diff,
최종86개 파일 지문/복구 tar, 전체 src 지문·원본XML·overlap JSON·로그·SQL과 실행 명령을 담았다.
상세 보고는 COORD `reports/I.md` 최신 절을 따른다. 로컬 재통합 검증은 완료했으며 관리자 최종 근거 리뷰와
그 이후 사용자 의사결정 감사가 남는다. CI·프런트·실공급사·공유DB·배포·커밋/게시를 완료로 주장하지 않는다.


## 2026-11-11 durable SSE 최종 통합 — 1,126개·V39·PG/SSE 실제 재시작 완료

Task `task_b626a0b32711`, dispatch `ctx_0c6236a9205e`. 사용자 승인된 durable SSE와 bounded poll의
L565를 원래 I542에 누적52개(추가24/수정27/삭제1)만 적용했다. 모든 before SHA와 모드를 확인하고
반영 직후565 exact를 검증했다. 옛 main 관찰 recorder는 명시된1개만 삭제했고 test fixture 대체본은 유지했다.
기존542/원본1083 및889개 증거/자기DB전체를 먼저 백업했다. L/F 원본·봉인 자료는 수정하지 않았다.
F source565/tools35는 archive로만 보존했으며 source로 가져온 F 도구는0개다.

권한 테스트의 `LIKE public`이 보존용public/V37을 참조하는 문제는 관리자 메시지 `msg_b43f9ba2963c`의
승인대로 현재 owner.getSchema()로 한 줄 수정했다. 별도 LOGIN·최소권한·timeline/health·8개 SQLSTATE42501은 유지됐다.
첫 전체 실행은103클래스1,126개 중1실패(오류/skip0)였다. 예약 취소 이벤트에 추가된 내부 deliveryKey가
서로 다른 UUID로 생성되어 기존 record 전체 비교가 실패한 fixture 문제였다. 원래6개 필드·한 번 발행·
예약상태/권한/멱등성 단언을 보존하고 실제 key의 UUID 형식을 검증하도록 테스트만 보완했다. 실패XML을 보존했다.
제품 main333파일·Journal/Repository·V1~V39 SQL38개는 입력과 동일하다.

새 `i_sse_full_v2`에서 17:27:30–17:28:35 KST 전체 `build --rerun-tasks --no-build-cache --offline
--no-daemon --max-workers=2 -Dorg.gradle.java.installations.auto-download=false`가 통과했다.
**103클래스1,126개/실패0/오류0/skip0**이다. 이전1083의 invocation multiset 대비9개 표시명 대체와
52개 추가로 순증43이며, 대체9개는 승인된 durable 동작으로 바뀐 WaitingEventHistory2개와 QueueNotifier7개다.
옛 recorder6개는 test-only 회귀 fixture로 남았다. 신규 durability28/migration2/operator1/bounded-poll12가 실제 포함됐다.
Hikari currentSchema=<new>,public과 URL schema override 미지정으로 pg_trgm 및 제한 LOGIN을 유지했고 cache4도 그대로다.

새 `i_sse_upgrade`에서17:32:00–17:32:16 KST 실제 V37→V39 적용/재기동 validate를 통과했다.
기존20테이블/16시퀀스/Flyway36행·payment TIMEOUT/refund UNKNOWN/알림outbox UNKNOWN이 전후 동일하다.
새 root/target/attempt/observation backfill0/capacity0이며 V39 이력38행이다. 기존36 SQL은 불변이므로
이전 V35→V37 보존 증거에 연결한다. V38→V39 projection·제한 비소유자 LOGIN 회귀도 전체에 포함됐다.

새 `i_sse_restart`에서17:32:41–17:33:06 KST 전달 활성64/250으로 강화 실제 재시작1회를 통과했다.
동일 PG GET ID1 진입0ms→fixture0.310/45.744ms→만료129.559ms→observer1295.766ms→kill1312.499ms,
진입/만료/observer종료 snapshot 모두 같은 entered_ns/active=true/응답시작·종료null이다.
공통 scheduling-1 예약EXPIRED/웨이팅NO_SHOW·C업무이력과 waiting-sse-1 실제 WAITING_EXPIRED 전송이 진행됐다.
별도 owner call의 WAITING_CALLED를 실제 wire로 보낸 뒤 결과저장 전 probe barrier에서 PID69382를 kill했다.
두 wire 전체 payload/eventId/generation/member/business event/root/target/attempt 조인을 원시자료에서 검산했다.
기존append14개 보존·해당 MARKED→UNKNOWN/LEASE_EXPIRED_AFTER_MARK, 재구독 재전송0, 고객수신UNKNOWN이다.
최종root DONE5/UNKNOWN1, target SENT1/SKIPPED3/UNKNOWN1, attempt SEND_RETURNED1/UNKNOWN1로 분모를 숨기지 않았다.
PG worker payment-worker-1과 SSE thread의 TX=false/연결lease0 및 별도 연결 NOWAIT 잠금 성공을 확인했다.
실제 JVM69382→69513→69557, 결제PARTIAL_REFUNDED/예약CONFIRMED/jobDONE/환불COMPLETED,
최초PAYMENT_ONLY/3000원/동일키/잔액7000원과 승인·환불효과 각1회(HTTP5회)를 유지했다.
SSE lease는 DB clock_timestamp SQL로, PG lease는 probe Clock 기준으로 가속했다. 실제30/120초 대기와
연속 wall clock을 입증하지 않으며, send 후 test barrier는 외부 네트워크 장애/고객ACK가 아니다.

원래7스키마 데이터·시퀀스256항목 동일, 새 실패/성공/upgrade/restart4스키마와 dump를 보존했다.
17:34:34 KST 앱18085/fake19085/legacy19084 listener0, 앱·Gradle0/DB앱세션0, 자기 fake회수 완료다.
JAR `2549ec1301868a80b62122582be21cb9c2a580233a14be5efd164f289a6ccc80`는 L 입력과 일치하며
V38/V39/설정 포함, 모든probe/test-only cache/옛 main 관찰 recorder 제외다. A/B main 그대로,
D main은 승인된 application.yml의 SSE 설정 추가 외 동일하다. 기존 C0연결 best-effort 성능은 역사이고
F565 성능을 합본 전체 부하/실PG/고객ACK/운영SLO로 확대하지 않는다. 추가 성능/전체F fault는 실행하지 않았다.

원시자료: `build/backend-improvement-integration/sse-final-20261111/`, 영구보존: COORD `evidence/I-sse-final/`.
최종 입력→I/이전I→최종 delta·source/mode manifest·복구 tar와 독립 요약은 `reports/I-sse-final.md`에 연결한다.
로컬 통합 검증은 완료했고 관리자 독립 delta/근거 리뷰 및 사용자 의사결정 감사는 남는다.
커밋/외부조회/게시/배포/실제PG·SOLAPI 및 원본 backport는 하지 않았다.
