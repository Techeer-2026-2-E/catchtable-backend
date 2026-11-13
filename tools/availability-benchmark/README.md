# 예약 가용성 비교 도구

> 아래 runner는 2026-11-11 측정에 사용한 고정 컨테이너·포트·스키마 전용 도구다. 2026-11-13 PR 분리 검증에서는 이 도구를 다시 실행하지 않았고, 별도 전용 PostgreSQL에서 전체 build만 검증했다. 새 성능 측정은 없다. [분리 검증 기록](../../work-log/2026-11-13-availability-pr-split.md)에서 그때의 결과와 과거 증거의 범위를 확인한다.

기준 `a44952e37993e5003c860714f126057cd311dba5`와 현재 작업 디렉터리의 `src`/빌드 설정을 각각 `git archive` 기반 산출물 폴더에 준비한다. 기본 저장소 checkout이나 추가 worktree는 사용하지 않는다. 동일 PostgreSQL 16 컨테이너에서 같은 스키마를 매번 재생성하고, 같은 합성 데이터·Clock·요청·JVM 조건으로 순차 실행한다. 실제 Toss/SOLAPI는 호출하지 않는다.

## 실행

Java 21, Docker, Python 3.9 이상이 필요하다. 컨테이너 `ct-improve-a-20261111`의 `127.0.0.1:55431`, DB `ct_improve_a`, 사용자 `catchtable`만 사용한다. `availability_benchmark` 스키마만 도구가 삭제·재생성한다. 다른 스키마나 컨테이너는 수정하지 않는다. 컨테이너가 중지되어 있으면 이 컨테이너만 시작한다.

```bash
# 이 전용 컨테이너의 일회용 로컬 비밀번호를 환경변수로 전달한다.
export AVAILABILITY_DB_PASSWORD='이 컨테이너의 로컬 테스트 비밀번호'

# 1차 기본: 작은 fixture와 각 버전 요청 1회, 워밍업·EXPLAIN ANALYZE 없음
python3 tools/availability-benchmark/run.py --smoke

# 도구 반복 검증: 한 실행의 같은 before/after 빌드 디렉터리에서 각각 2회, 총 4회
python3 tools/availability-benchmark/run.py --smoke --repetitions 2

# 2차: 관리자가 순차 성능 측정을 배정한 뒤 실행
python3 tools/availability-benchmark/run.py --measure --repetitions 3 --warmup 50 --samples 200
```

각 실행에서 `./gradlew test --tests com.catchtable.reservation.benchmark.AvailabilityBenchmarkTest --init-script ... --no-daemon --max-workers=2 --console=plain`을 사용한다. Java 테스트 소스는 평소 `src/test`에 포함되지 않으며 init script로 명시적으로 추가한다. 평소 회귀 테스트에서 skip하는 성능 테스트를 만들지 않는다. benchmark init script를 사용하는 `test` task는 up-to-date 판정과 build cache 복원을 비활성화해 반복마다 실제 실행한다. 컴파일 등 선행 task의 재사용은 유지한다.

## 조건과 집계 단위

| 항목 | smoke 기본값 | measure 기본값 |
| --- | --- | --- |
| 매장 / 매장당 테이블 | 1 / 4 | 3 / 40 |
| 예약 이력 날짜 수 | 2 | 14 |
| 전체 테이블 / 예약 행 | 4 / 48 | 120 / 10,080 |
| 후보 슬롯 | 53 | 53 |
| 영업 / 이용 / 간격 | 09:00~23:00 / 60분 / 15분 | 동일 |
| Clock | 2030-01-20 09:00 Asia/Seoul | 동일 |
| 요청 | 첫 매장, 2030-01-21, 2명 | 동일 |
| 워밍업 / 표본 / 반복 | 0 / 1 / 1 | 50 / 200 / 3 |
| JVM heap / test fork | 512 MiB / 1 | 동일 |

`--repetitions`를 지정하면 두 모드 모두 해당 횟수만큼 before/after 쌍을 반복한다. 생략 시 smoke는 1회, measure는 3회다. smoke는 반복 수와 무관하게 작은 fixture·워밍업 0·표본 1을 유지한다.

fixture는 활성·비활성 테이블, 최소 인원 제한, 확정·취소·만료·미승인 만료 선점을 섞는다. 각 테이블에는 날짜별로 겹치지 않는 예약 6개를 넣고 fixture 생성 후 `ANALYZE`한다. 승인 진행·결과 불명·격리는 별도의 실제 DB 회귀 테스트에서 단건·batch 의미를 비교한다.

- 요청 경로: `GET /api/user/stores/{storeId}/availability?date=2030-01-21&partySize=2`.
- **총 SQL**: 성공한 MockMvc 요청 스레드에서 Hibernate `StatementInspector`가 관찰한 SQL 준비 호출 수. 현 조회 경로의 매장·요일/특별 영업일·수량 SQL이 모두 포함된다. **수량 SQL**은 그중 `count(`와 `tstzrange`를 포함하는 SQL 수다. SQL 내부 반복, 영향받은 행 수, TCP 왕복 수와는 다른 단위다.
- fixture 작성·ANALYZE·트랜잭션 제어 SQL·앱 시작/Flyway·다른 스레드 스케줄러·EXPLAIN은 SQL 집계와 요청 시간에서 제외한다. SQL 실행 오류는 성공 요청으로 보고하지 않는다.
- 시간은 `MockMvc.perform` 전체 경과 시간으로 MVC 바인딩, 서비스 트랜잭션, JDBC 조회, 응답 직렬화를 포함한다. 실제 HTTP 소켓·네트워크·브라우저 시간은 포함하지 않는다. 앱 포트 18081과 HTTP 포트 19081에 서버를 띄우지 않는다.
- 모든 측정 요청의 응답 JSON을 비교하고, 매 반복의 before/after 전체 응답도 동일해야 성공한다. 오류율과 p50/p95/p99는 각 버전·반복별로 출력하며 백분위는 nearest-rank다. smoke에는 시간 분포 요약을 만들지 않는다.
- 반복별 순서는 before→after, after→before로 교대한다. 같은 실행의 `before/`, `after/` 빌드 디렉터리를 재사용하되, benchmark test는 매번 새 Spring/JVM 프로세스와 같은 내용의 새 스키마에서 실행한다. 각 반복의 XML·samples.json·response.json은 새로 생성되어야 한다.
- 실제 캡처한 수량 SQL을 같은 fixture와 바인딩으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`한다. before는 슬롯별 모든 수량 SQL, after는 일괄 SQL 1건이다. 요청 시간 측정이 끝난 뒤 실행한다. **1차 smoke는 이 경로를 실행하지 않는다.**

## 원시 결과와 한계

`build/availability-benchmark/<timestamp>/`에 다음을 남긴다.

- `before/`, `after/`: 실제 빌드 입력과 `source-hashes.json`.
- `run.json`, `input.patch`: 기준 HEAD·조건과 기존 tracked 파일의 diff. 신규 파일은 after snapshot과 파일별 해시에 포함된다.
- `<반복>-<버전>/samples.json`: 표본별 status, elapsedMs, totalSql, countSql.
- `response.json`, `request.sql`, `metadata.json`: 응답, 첫 성공 요청의 SQL, Java/PG/heap/데이터 규모·DB 누적 카운터·종료 시점 잠금 대기 수.
- `plans.json`: 2차에서 실행하는 각 수량 쿼리의 JSON plan 문자열.
- `resources-before.json`, `resources-after.json`: 호스트 load average, 전용 컨테이너의 CPU·메모리 등 Docker stats 시점값.
- `command.json`, `gradle.log`, `test-results/`, `summary.json`: 재현 명령·테스트 원본·집계.

이 도구는 동시 사용자 부하나 최대 처리량을 측정하지 않는다. DB 카운터는 누적값이고 잠금 대기·자원값은 시점값이어서 최대값이나 대기 시간 분포가 아니다. 실제 예약 생성 정합성은 기존 별도 연결 동시성 회귀가 담당한다. 같은 호스트의 다른 작업과 OS/DB 캐시, JVM 컴파일 영향이 남으므로 관리자의 순차 배정 뒤 반복 결과와 plan을 함께 해석해야 한다. 인덱스 필요성과 실행 비용 개선은 2차 측정 결과로 판단한다.
