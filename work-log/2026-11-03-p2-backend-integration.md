# 기능10·53·54·55·56·60 백엔드 로컬 통합 검증

- 작업일: 2026-11-03
- 관련: [예약금 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [선점 #60](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/60), [만료시간 #62](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/62), [반환 #63](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/63), [재예약 #64](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/64), [환불 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)·[#188](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/188)
- 기준: `origin/dev@f12ebb26ce6f13417ab359ea7fdaa38717bf1bc3`, 독립 `test/p2-backend-integration` 브랜치
- 전체 빌드 입력 HEAD: `84b8bc00480e53d6e2eb6c436605602a4becdb5c`

## 통합 범위

세 선행 작업이 모두 성공으로 정산된 뒤 실제 보고서·XML·diff를 확인했다. paid-flow는 기존 검증으로 충분해 새 커밋이 없다. [선점 경계 보강](62-hold-lifecycle-boundaries.md) `35f1be6a15c76e0fb476510c238049c1648d67b3`을 `0cbfd65edf968b15245e905ea927973b36b23681`로, [완료 환불의 늦은 응답 수정](188-reservation-refund-execution.md) `d411c2b2eb11683d618df348a8865c0729a0bc12`를 `84b8bc00480e53d6e2eb6c436605602a4becdb5c`로 각각 `cherry-pick -x`했다. 충돌이나 추가 운영 코드 수정은 없다.

착수 시 HEAD·origin/dev·`git ls-remote origin refs/heads/dev`가 위 기준과 일치했다. [PR #235](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/235) head `b06eb9557c25892f3bf365f618d2fc7c8662964a`는 이미 dev의 조상이므로 별도 merge나 재구현을 하지 않았다. 선행 작업의 `54119eb3` 기준 결과와 이번 최신 dev 합본 결과를 구분한다.

운영 변경은 `PaymentRefundService`의 성공 반영 전에 저장된 완료 결과를 재생하는 3줄이다. 같은 키의 늦은 응답이 후속 환불 누적액을 다시 적용하지 않도록 기존 잠금 안의 `completedResult`를 재사용한다. 나머지 변경은 선점 경계·동시성 및 늦은 환불 응답의 테스트와 기능별 기록이다. 기존 마이그레이션·설정·식별·상태·Clock·잠금·커밋 후 SSE 계약은 바꾸지 않았다.

## 이번 전체 검증

Java Temurin 21.0.12.1과 Gradle Wrapper를 사용했다. 새 일회용 `postgres:16` 컨테이너 `ct-p2-backend-integration-20261103-3990d780`의 빈 `p2_integration` DB를 자동 배정 `127.0.0.1:49382`로 연결했다. 실제 PostgreSQL 16.15, 기본 연결 한도100, tmpfs·loopback 전용 trust 인증이며 TCP `SELECT 1`과 public 테이블0개를 확인한 뒤 시작했다. Toss 키는 빈 값이고 base URL은 loopback이다.

```sh
JAVA_HOME=/Users/geonha/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home \
DB_HOST=127.0.0.1 DB_PORT=49382 DB_NAME=p2_integration \
DB_USERNAME=p2_integration DB_PASSWORD= \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:9 \
/Users/geonha/.codex/skills/test-build-log-summary/scripts/quiet-run.sh \
./gradlew clean build --no-daemon --max-workers=2
```

포트는 이번 실행의 임시 값이다. 전체 실행은 한 번이며 exit0, 72클래스804개가 실패·오류·건너뜀0으로 통과했다. 선행 작업의 통과 횟수는 합산하지 않았다. PR235 클라이언트18개, 원본 소비35개·미연결2개, prepare54개, 예약 승인46개, 선점 동시성6개, 환불 실행42개·재시작2개를 포함한다. 테스트 원본 공급자와 fake/mock/loopback에 대한 결과다.

기존 `populatedV22WithoutV20StillRejectsUpgradeBeforeAnyNewSchemaChange`도 통과했다. V20 누락 이력을 우회하지 않았으며, 빈 DB 성공을 기존 공유 DB 업그레이드 성공으로 판단하지 않는다. 기존 마이그레이션27개는 기준·빌드 후·운영 JAR에서 byte 동일했고 Flyway27개 성공 및 예약 시간 GiST 배타 제약을 확인했다. 종료 전 예약·결제·환불·관련 이력·회원·매장 fixture는 모두0건이었다.

bootJar SHA-256은 `a1913699a79ecd08c3f36e769e453e1e39512f9b95ca30d66e3a8ecbb672c050`이다. 운영 클래스227개와 컴파일된 테스트 클래스101개의 교집합은 없고, 테스트 원본 공급자·fake·TestConfiguration도 패키징되지 않았다. 실제 기능33 공급자는 여전히 없다. 자기 컨테이너만 중지해 tmpfs와 함께 제거했고 동일 이름의 잔존 컨테이너0개를 확인했다.

전체 로그·XML·HTML·명령/환경·입력 해시·JAR/DB 확인은 공통 Git 디렉터리의 `orca-artifacts/run_8fadb495b2f1/p2-backend-integration/`에 보존했다. 핵심 파일은 `01-clean-build/{command.json,result.json,summary.json,xml/,logs/}`, `build-state.json`, `jar-audit.json`, `flyway-after.csv`, `reservation-exclusion-constraint.csv`, `container-cleanup.json`이다. 빌드 입력345개 manifest SHA-256은 `d9e6c5cabf7343b4f28383a7b5c92ca0e9f31ecf76e053b0db6585900aaa3685`이며 이후에는 문서만 추가했다.

## 남은 범위

기능10·53의 신뢰 원본 소비→prepare→승인과 스냅샷 보존, 기능54·55·56의 서버 기한·보호·반환·재예약, 기능60의 환불/취소 정산은 위 백엔드 테스트 범위에서 검증됐다. 완료 조건별 상세 근거와 선행 실패 기록은 같은 run의 `p2-paid-flow/report.md`, `p2-hold-lifecycle/report.md`, `p2-refund-settlement/report.md`에 있으며 이번804개 XML이 합본의 최종 실행 증거다.

실제 기능33 설정 공급자와 그 원본부터의 흐름, 실제 Toss, 프런트, 공유/개발 DB 이력·적용, CI·배포는 미검증이다. 새 공급자·설정·스키마·외부 게시를 추가하지 않았고 상위 기능 전체 완료를 선언하지 않는다. 추가 XML 매핑용 임시 점검은 parameterized 테스트의 표시 이름과 메서드명이 달라 단언에 실패했으나 앱 테스트 실패가 아니며, 추가 자동화 없이 실제 XML과 선행 보고서를 근거로 사용했다.
