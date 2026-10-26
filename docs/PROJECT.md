# CatchTable 백엔드 프로젝트 기준

> 확인일: 2026-10-27. 구성 기준은 `dev`의 `e6c668bbb6be920cb2ff47042e82edfe832c0a18`이다. 이후 리뷰 수정·삭제 경합을 보완하고 원격 브랜치·보호 설정과 격리된 PostgreSQL에서 빌드·테스트를 확인했다. 변경과 검증 근거는 [리뷰 경합 수정 기록](../work-log/2026-10-27-review-delete-race.md)에 남긴다. 배포·프런트 연동 확인은 포함하지 않는다.

## 목적과 구성

고객의 레스토랑 예약·웨이팅과 점주의 매장 운영을 지원하는 Techeer 2026-2 E팀 백엔드다. 기능별 범위·담당·Phase는 Notion 기능명세에서 확인한다.

| 구성 | 역할과 근거 |
| --- | --- |
| Java 21 / Spring Boot 3.3.4 / Gradle Wrapper | [build.gradle](../build.gradle) |
| PostgreSQL 16 / Spring Data JPA / Flyway | [Compose](../docker-compose.yml), [앱 설정](../src/main/resources/application.yml), [마이그레이션](../src/main/resources/db/migration) |
| `member`, `store` | 회원, 매장·테이블·메뉴·영업시간·예약 정책 |
| `reservation`, `waiting` | 예약·선점·변경, 웨이팅 접수·호출·응답·착석·종료 |
| `history`, `review`, `notification` | 이용 내역, 리뷰, SSE 알림 |
| `global` | 회원 식별, 공통 설정과 예외 처리 |

코드는 [src/main/java/com/catchtable](../src/main/java/com/catchtable), 테스트는 [src/test/java/com/catchtable](../src/test/java/com/catchtable)에 있다. 이 표는 코드 구성 안내이며 모든 기능의 프런트 연동·배포 완료를 뜻하지 않는다.

## 문서별 역할

| 주제 | 정본 또는 확인 위치 | 갱신 시점 |
| --- | --- | --- |
| 범위·담당·Phase | [Notion 기능명세](https://app.notion.com/p/58d48d76fa32831298f7814c5b8b0a14) | 팀에서 기능·담당·단계를 바꿀 때 |
| 문제·시나리오·완료 조건 | [PRD](https://app.notion.com/p/3ef48d76fa3281bb9abcd0c7fa609157) | 요구사항을 정하거나 바꿀 때 |
| 기술 요구사항·선택 근거 | [Engineering Requirements Document](https://app.notion.com/p/3ef48d76fa328188b64ce9c4386863f5) | 설계를 검토·결정할 때 |
| API 계약 | [Notion API](https://app.notion.com/p/5cb48d76fa3283e7b711010662ec4e75) | 요청·응답·상태·이벤트 계약을 바꿀 때 |
| DB 설계 | [Notion DB ERD](https://app.notion.com/p/40748d76fa32839aa437011845ab7f7b) | 스키마를 바꿀 때. 적용 증거는 마이그레이션·DB에서 확인 |
| 구성·실행·검증 | 이 문서와 실제 설정 파일 | 해당 설정을 바꾼 작업자가 갱신 |
| 실제 구현 | 현재 브랜치의 코드·마이그레이션·테스트 | 변경할 때 |
| 진행·리뷰·머지 상태 | [GitHub 이슈](https://github.com/Techeer-2026-2-E/catchtable-backend/issues), [PR](https://github.com/Techeer-2026-2-E/catchtable-backend/pulls) | 해당 서비스에서 관리 |
| 변경의 이유와 검증 | [work-log](../work-log/README.md), [기존 의사결정 기록](development-decisions.md) | 중요한 변경을 마칠 때 |
| 미결정·불일치 | [확인할 사항](OPEN_ITEMS.md)에서 원본 항목으로 연결 | 차이를 발견하거나 해결할 때 |

Engineering Requirements Document와 DB의 Entity Relationship Diagram을 모두 ERD라고 부르므로 문맥을 구분한다. 문서 상태의 검토 완료와 기능 구현 완료도 구분한다. 원문과 코드가 다르면 양쪽 근거를 적고 해당 팀 결정으로 조정한다.

## 로컬 실행

JDK 21과 Docker Compose가 필요하다. Gradle은 저장소의 `./gradlew`를 사용한다.

```bash
docker compose up -d postgres
docker compose ps postgres
./gradlew bootRun
```

기본값은 앱 `8080`, DB `localhost:5433`, DB명·사용자 `catchtable`이다. 비밀이 아닌 로컬 기본값과 변수 이름은 [.env.example](../.env.example), 실제 앱 연결은 [application.yml](../src/main/resources/application.yml)에서 확인한다.

`.env`는 Compose가 읽는다. `./gradlew`로 실행하는 Spring 앱에는 같은 값을 셸 환경변수로 별도 전달해야 한다. 포트·DB명·계정을 바꿨다면 앱과 아래 테스트 명령에도 동일하게 반영한다. 기존 다른 작업의 `catchtable-postgres` 컨테이너가 있으면 먼저 그 사용자를 확인한다.

정상 확인은 기본 포트 기준 `curl --fail http://localhost:8080/actuator/health`로 한다. 목 계정·매장 데이터는 앱 시작과 Flyway 적용 후 [README의 목 데이터 절차](../README.md#로컬-예약-api-확인)를 따른다. 테스트 전에 목 데이터를 넣을 필요는 없다.

## 검증

통합·동시성·마이그레이션 테스트는 실제 PostgreSQL을 사용하며 데이터를 쓰거나 스키마를 만든다. 보존할 데이터가 없는 개발자 전용 테스트 DB를 사용한다. 운영·공용 DB를 연결하거나 개발 DB의 데이터를 지워 테스트 환경을 만들지 않는다.

아래 예시는 로컬 Compose의 기본 포트·계정 기준이다. 테스트 DB는 처음 한 번 만든다. `catchtable_test`가 이미 있으면 본인의 테스트 전용 DB인지 확인하고 생성 단계를 생략한다.

```bash
docker compose up -d postgres
docker compose exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" catchtable_test'
DB_HOST=localhost DB_PORT=5433 DB_NAME=catchtable_test DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew build
```

| 목적 | 명령·확인 위치 |
| --- | --- |
| 전체 검증 | 위 환경변수와 `./gradlew build`. 테스트와 패키징 성공을 확인 |
| 관련 테스트 먼저 실행 | 같은 환경변수와 `./gradlew test --tests 'com.catchtable.reservation.service.ReservationConcurrencyTest'` 등 실제 테스트 클래스 지정 |
| 테스트 결과 | 실행 후 `build/reports/tests/test/index.html`, `build/test-results/test/` |
| 문서·양식 변경 | 상대 링크·파일 경로, 명령과 현재 설정의 일치, YAML·import 구문, `git diff --check` |

명령·DB 대상·성공/실패·미실행 이유를 PR 또는 work-log에 남긴다. 기존 [CI](../.github/workflows/ci.yml)는 JDK 21·PostgreSQL 16 서비스에서 `./gradlew build`를 실행한다. 로컬 명령이 같더라도 CI 실행 결과를 대신하지 않는다.

## 꼭 유지할 경계

- 예약과 웨이팅은 별개다. [ReservationStatus](../src/main/java/com/catchtable/reservation/entity/ReservationStatus.java)와 [WaitingStatus](../src/main/java/com/catchtable/waiting/entity/WaitingStatus.java)의 철자와 상태 의미를 확인한다.
- 테이블 배정은 행 잠금과 시간 중복 제약을 함께 사용한다. 관련 코드·테스트를 확인하고 중복 예약 방지를 보존한다.
- 시간은 기존 `Clock`과 매장 시간대 `Asia/Seoul`을 따른다. 선점 만료·예약 시각·도착 제한의 경계가 중요하다.
- `X-Member-Id`와 고정 목 계정은 인증 연동 전 계약이다. 모든 API가 같은 방식이라고 가정하지 않는다.
- SSE 수신·재연결 뒤 재조회 계약과 커밋 후 알림을 보존한다. 현재 메모리 기반 연결을 여러 서버 간 전달 구조로 간주하지 않는다.
- 선점 코드의 존재를 외부 결제·환불 연동 완료로 쓰지 않는다. 결제와의 연결 조건은 실제 공통 계약을 확인한다.

## 협업 기준

[팀 Git 규칙](https://app.notion.com/p/bf748d76fa32835d8c0001bcac5895ea)을 따른다. 기능·수정 브랜치에서 `dev`로 PR을 만들고, 발표·배포 통합은 `dev` → `main`으로 진행한다. 브랜치는 `feat/`, `fix/`, `docs/` 등 작업 종류와 짧은 설명을 사용하고 이슈 번호가 있으면 연결한다. `dev-2`는 이번 로컬 작업 브랜치이며 팀 통합 브랜치명이 아니다.

커밋·PR 제목은 `<type>: <설명>` 형식이다. 팀 규칙의 머지 기준은 리뷰어 최소 1명 승인과 CI 통과다. 2026-10-27 GitHub API 조회에서 `main`과 `dev`의 `protected: false`를 확인했다. 팀 규칙과 GitHub의 강제 설정은 구분한다. 오래된 코드컨벤션의 `develop`·Python식 규칙과의 차이는 [확인할 사항](OPEN_ITEMS.md)에 남겼다.

배포 환경·운영 절차는 이번 구성에서 확인하지 않았다. 기능 완료·테스트 통과만으로 배포되었다고 기록하지 않는다. 이 문서 묶음과 외부 Notion 문서 사이에 자동 동기화는 없다.
