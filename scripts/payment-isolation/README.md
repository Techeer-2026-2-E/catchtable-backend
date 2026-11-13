# PG 장애 격리 검증 도구

실제 Toss와 SOLAPI를 호출하지 않는다. PostgreSQL16 `ct-improve-d-20261111`, `127.0.0.1:55434`, DB `ct_improve_d`의 **`d_probe` 스키마만 매 조건마다 재생성**한다. 앱 `18084`, fake PG `19084`가 이미 사용 중이면 실행을 중단한다. 테스트 전용 값만 쓰며 `.env`나 실제 결제 키를 읽지 않는다.

## 1차: 기능 smoke / 기준 소스 준비

워크트리 루트에서 JDK21을 선택한 뒤 실행한다. Gradle은 기존 캐시만 사용하는 `--offline --no-daemon --max-workers=2`로 실행한다. 출력 폴더는 새 이름이어야 하며, 실행 전 기존 `d_probe`를 덤프해 보존한다.

```sh
python3 scripts/payment-isolation/run.py --mode smoke --output build/payment-isolation/process-smoke-new
python3 scripts/payment-isolation/run.py --mode prepare-baseline --output build/payment-isolation/baseline-new
python3 scripts/payment-isolation/run.py --mode validate-baseline --output build/payment-isolation/baseline-fixture-new
```

- smoke는 실제 앱 JVM의 승인 응답 유실 → webhook durable 저장 → 작업 중 JVM kill → lease 만료 처리 → 재시작 → 승인/예약 확정과 환불 UNKNOWN → JVM 재시작 → 동일 키/금액 복구를 검사한다. 별도 파일에 저장되는 fake PG 원장상 승인·환불 효과 각각 1회여야 한다.
- PG 응답을 기다리는 중 실제 예약 hold 및 waiting expiry 스케줄이 실행되는지 검사한다. 별도 DB 연결로 reservation/payment/job 행에 `FOR UPDATE NOWAIT`가 성공해야 한다.
- 프로세스 smoke는 120초를 실제로 기다리는 대신 자신의 job lease만 SQL로 만료시킨다. 정확한 119/120초 경계·동시 claim·stale ack는 JUnit의 제어 가능한 Clock 검증이다.
- `validate-baseline`은 기준본의 동일 fixture를 실제 앱에서 기동하고 세 조회 API/웹훅과 heartbeat 메트릭 가용성만 검사한다. 부하나 latency 비교는 하지 않는다.
- `prepare-baseline`은 `a44952e37993e5003c860714f126057cd311dba5`를 `git archive`로 출력 폴더에 추출하고 동일 probe 파일을 추가해 컴파일한다. 기본 저장소나 다른 워크트리를 checkout하지 않는다.
- `PaymentIsolationProbe`는 `probe.gradle`로 추가되는 테스트 클래스다. 운영 bootJar에는 들어가지 않는다. 앱 Clock은 JVM 시작 시 `2030-01-20T12:00+09:00`으로 맞춘 뒤 실제 경과 시간만큼 흐른다. 기본 TaskScheduler 100ms heartbeat의 늦어진 시간을 별도 측정한다.

## 2차: 관리자 순차 배정 후 성능 비교

**1차에서는 이 명령을 실행하지 않았다.** 같은 호스트의 다른 큰 테스트가 끝난 뒤 순차 실행한다.

```sh
python3 scripts/payment-isolation/run.py --mode compare \
  --output build/payment-isolation/comparison-new \
  --requests 4000 --warmup 400 --rate 100 --repeats 3
```

각 반복은 before/after × 정상(0ms)/PG 지연(2000ms) 네 조건이다. 같은 fixture와 요청 순서, 시작 Clock, JVM heap 256–512MiB, Tomcat max40/min4, Hikari10, connect500ms/read3s, 전체 호출3.5s(수정본), PG 한도8(수정본), 워밍업400/본표본4000/도착률100RPS를 사용한다. 기준본에서 없는 설정은 효과가 없다. seed는 매 조건마다 동일하게 재설정하며 메트릭과 실제 외부효과를 DB로부터 추정하지 않는다.

fixture 규모는 회원2, 매장1, 테이블100, 선점100, 결제100, 웨이팅1, 영업시간7이다. 결제 2–100은 진행 중 승인 조회 대상으로 남는다. 다음 요청을 같은 순서·비율로 보낸다.

| 비율 | 경로 | 역할 |
|---|---|---|
| 25% | GET `/api/user/stores?size=10` | 매장 조회 |
| 25% | GET `/api/user/reservations/1` | 예약 상태 조회 |
| 25% | GET `/api/user/waitings/1` | 웨이팅 순번 조회 |
| 25% | POST `/api/user/webhooks/payments` | 99개 결제에 반복 식별 힌트 전달 |

일반 API는 **조회 부하**다. 신규 예약/웨이팅 생성 처리량을 이 결과로 주장하지 않는다. 승인·환불 HTTP 및 예약 확정·만료는 기능 smoke/회귀에서 별도 검증한다. 생성 부하를 비교하려면 독립 고객·슬롯 fixture와 동일 생성 요청을 추가한 후 다시 배정받는다.

결과 파일:

- `requests.json`: 모든 요청의 HTTP 상태, 실행 latency, 예정 도착 대비 실제 시작 지연. 클라이언트 최대256 스레드; `client_start_lag_ms` 증가 시 목표 도착률을 실제 제공하지 못한 한계로 기록한다.
- `summary.json`: API별 n, nearest-rank p50/p95/p99(ms), 2xx가 아닌 오류율. 네 조건·반복을 따로 보존하고 평균으로 꼬리 지연을 숨기지 않는다.
- `resources.json`: Tomcat busy/current, Hikari active/pending, PG active/admitted/rejected, JVM thread, 공용 scheduler heartbeat 지연, fake PG active/peak/calls, `pg_stat_activity` active/idle-in-tx/Lock 대기 수. SQL 결과는 해당 DB 전체의 표본이며 profiler 수준의 모든 lock hold 시간을 뜻하지 않는다.
- 메트릭 표본은 완료 후 250ms 간격으로 다시 수집한다. HTTP pool 포화 시 수집 자체가 늦어질 수 있으므로 실제 `at` 간격 및 `sampling_error`를 함께 본다. 기준본의 신규 PG 메트릭은 null이고 fake PG 값은 양쪽에서 있다.
- `environment.json`, 앱 로그, 별도 `pg-ledger.json`을 보존한다. 앱/PG는 도구가 만든 child PID만 종료하고 DB 컨테이너와 결과 스키마는 후속 검토를 위해 남긴다.

동일 호스트에서 다른 작업의 CPU·DB·메모리 영향, 작은 fixture, 앱 내 프로세스 단위 cap, fake PG 동작은 실제 운영 부하와 다르다. 이번 1차는 비교 도구 준비와 기능 검증이며 성능 개선 수치는 없다.

## 2026-11-11 I 검증 도구 반영과 D 실행환경

I가 검증한 `run.py`, `fake_pg.py`, `PaymentIsolationProbe.java`만 전달 manifest의 SHA256과 대조해 반영했다. 환경 분리·offline·중복 CLI 속성 대체·기동 실패 조기 감지와 smoke의 동일 PG 요청 미응답 관찰을 포함한다. compare의 fixture·요청비율·timeout·PG 지연·실행 순서는 유지한다. fake의 요청별 관찰은 smoke에서만 켜지며 compare에서는 꺼진다. probe가 scheduler bean과 공통 thread를 로그에 남긴다.

환경변수 `PAYMENT_PROBE_CONTAINER`, `PAYMENT_PROBE_DB_PORT`, `PAYMENT_PROBE_DB_NAME`, `PAYMENT_PROBE_DB_USER`, `PAYMENT_PROBE_DB_PASSWORD`, `PAYMENT_PROBE_SCHEMA`, `PAYMENT_PROBE_APP_PORT`, `PAYMENT_PROBE_PG_PORT`를 지원한다. 기본값은 이 문서 첫 절의 D 할당 자원이며 public 스키마는 거부한다. 다른 담당자의 DB/포트로 실행하지 않는다. `start(..., extra_args=...)`는 같은 CLI 속성을 덧붙이지 않고 기존 값을 대체한다.

이번 D 실행은 Java21.0.12.1, `JAVA_TOOL_OPTIONS` 비움, 위 D 환경을 명시한다. `build/payment-isolation/performance-20261111/launch_observed.py`는 동일 compare CLI를 실행하면서 호스트 loadavg·프로세스 CPU/RSS·vm_stat을 약1초 간격으로 별도 기록한다. 요청 조건을 바꾸지 않는다. 첫 작은 비교는 `--requests 40 --warmup 4 --rate 20 --repeats 1`, 검산 후 본 비교는 위4000/400/100/3을 새 폴더로1회 실행한다. 실제 수치·실패·계측 한계는 [작업 기록](../../work-log/2026-11-11-payment-isolation.md)에 남긴다.

2026-11-11 D 독점 슬롯에서 작은 비교160요청과 본 비교48,000요청을 완료했으며 업무 요청은 모두200이었다. 기준본 PG 지연에서는 클라이언트 포화와 메트릭 timeout60건이 함께 발생했다. 정확한 반복별 수치와 해석 한계는 위 작업 기록을 따른다. 원본 도구·증거와 기존 d_probe는 별도 압축본/SQL dump로 보존했다.


## 2026-11-11 통합 검증용 실행 환경 분리

`run.py`는 `PAYMENT_PROBE_CONTAINER`, `PAYMENT_PROBE_DB_PORT`, `PAYMENT_PROBE_DB_NAME`,
`PAYMENT_PROBE_DB_USER`, `PAYMENT_PROBE_DB_PASSWORD`, `PAYMENT_PROBE_SCHEMA`,
`PAYMENT_PROBE_APP_PORT`, `PAYMENT_PROBE_PG_PORT`를 지원한다. 지정하지 않으면 위 D 기본값이다.
스키마는 `public`을 허용하지 않으며 fixture 단계는 지정된 전용 스키마만 재생성한다.
실제 데이터를 보존할 스키마를 지정하지 않는다. fake PG에도 동일 포트가 전달된다.
Gradle에는 `--offline --no-daemon --max-workers=2`가 적용된다.
통합 전용 실행은 [정확성 검증 도구](../backend-improvement-integration/README.md)를 따른다.
probe는 실제 scheduler bean·공통 heartbeat thread를 로그에 남긴다. 운영 JAR에는 포함되지 않는다.

## I-SPEC-1: 같은 PG 요청의 대기 구간 확인

smoke는 결제1의 GET `/v1/payments/probe-key-1`만 관찰하도록 fake PG에 요청한다.
서버가 부여한 요청 ID·method/path·진입 시각을 확인한 뒤에만 만료 fixture를 넣는다.
예약/웨이팅 만료 후와 C 이력·thread dump observer 반환 후에도 **같은 요청**이 active이고
응답 시작/종료 시각이 모두 비어 있는지 확인한다. 요청 교체, 이미 시작한 응답, 종료된 요청은 실패다.
PG delay/timeout을 늘리거나 서버 응답을 강제로 붙잡는 gate는 추가하지 않았다.

`scheduler-overlap.json`은 진입 확인 → fixture 시작/완료 → 만료 확인 → observer 완료 → JVM kill의
순서와 요청 snapshot을 단계마다 저장한다. 검사를 모두 마쳐야 `overlap_verified=true`가 된다.
기존 실제 kill/wait·lease 가속·재시작 복구·원장 효과/상태 단언은 유지한다.
관찰은 smoke에서만 opt-in이며 compare는 요청 관찰을 활성화하지 않는다. 비교 요청·지연·표본 조건은 그대로다.
이번 추가본은 C 독점 성능 슬롯 때문에 **정적 검사만 완료했고 실행 검증은 대기**다.

2026-11-11 후속: 위 실행 대기는 종료됐다. 새 `i_restart_spec1`/`07-restart-spec1`에서 동일 GET의 진입→fixture→만료/observer 완료 중 미응답→kill 및 재시작을 실제1회 검증해 통과했다. 자세한 결과와 남은 범위는 [통합 기록](../../work-log/2026-11-11-backend-improvement-integration.md)의 마지막 절을 따른다.

2026-11-11 최종 I 재통합에서는 Gradle build 인수에 `-Dorg.gradle.java.installations.auto-download=false`를 추가했다.
이는 캐시된 Java21 사용을 명시하는 도구 보완이며 compare의 함수·요청/지연/표본 조건은 그대로다.
최종 C 묶음 저장을 포함한 전체1,083개와 강화된 restart는 위 [통합 검증 도구](../backend-improvement-integration/README.md)의 최종 절을 따른다.


2026-11-11 durable SSE 후속 I 검증은 [최종 통합 도구](../backend-improvement-integration/README.md)의
`verify_sse.py`를 사용한다. smoke에 선택적 start/kill/restart callback과 I opt-in 계측을 추가했으며,
기존 PG 동일 요청 미응답·결제/환불 효과·상태 단언과 compare 경로는 유지한다. F 전체부하를 재실행하지 않는다.
