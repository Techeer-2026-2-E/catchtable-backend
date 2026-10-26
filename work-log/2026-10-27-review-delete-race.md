# 리뷰 수정·삭제 경합 방지와 main 통합

- 작업일: 2026-10-27
- 구현 기준: `dev`의 `e6c668b`, 공통 문서 로컬 커밋 `1386f70`
- 요청: 검사에서 확인한 리뷰 경합을 고치고 `dev`와 공통 문서를 검증한 뒤 `main`에 반영
- 관련 PR과 최종 CI 결과는 이 기록을 연결한 GitHub PR에서 확인한다.

## 배경과 목표

리뷰 수정이 삭제 전 데이터를 읽은 뒤, 삭제 요청이 먼저 커밋되면 나중에 저장되는 수정이 `deleted_at`을 다시 `NULL`로 덮었다. 삭제된 리뷰가 목록에 다시 노출되는 결과를 실제 PostgreSQL에서 재현했다. 기존 287개 테스트에는 이 요청 간 경합 검사가 없었다.

## 판단과 근거

수정과 삭제는 [같은 조회 메서드](../src/main/java/com/catchtable/review/repository/ReviewRepository.java)를 사용한다. 이 조회에 `PESSIMISTIC_WRITE`를 적용해 같은 리뷰의 두 요청을 트랜잭션 종료까지 순서대로 처리한다. 새 버전 컬럼·마이그레이션이나 API 계약 변경 없이 기존 권한 검사와 삭제 재요청 동작을 유지한다.

낙관적 잠금도 가능하지만 버전 컬럼과 충돌 응답 처리가 추가된다. 현재 두 쓰기 경로만 직렬화하면 문제를 막을 수 있어 공통 조회의 행 잠금을 선택했다.

## 실제 변경

- `ReviewRepository.findByIdAndReservation_Member_Id`: 수정·삭제의 공통 조회에 행 잠금 적용.
- [ReviewConcurrencyTest](../src/test/java/com/catchtable/review/service/ReviewConcurrencyTest.java): 수정 트랜잭션이 열린 상태에서 다른 트랜잭션의 삭제를 실행하고 최종 삭제 표시가 보존되는지 확인.
- 테스트는 PostgreSQL의 짧은 로컬 잠금 제한으로 두 요청의 순서를 제어한다. 기다리던 삭제는 수정 커밋 뒤 재시도하며 임의의 sleep에 의존하지 않는다. 운영 설정은 바꾸지 않는다.
- 프로젝트 기준과 원격 보호 설정의 확인 범위를 갱신했다. 옛 작업 폴더 4곳과 문서 전용 `main` 대안 브랜치의 내용은 이번 구현에 섞지 않는다.

## 검증

| 확인 | 실제 명령·환경 | 결과 |
| --- | --- | --- |
| 수정 전 회귀 재현 | 격리된 PostgreSQL 16, `./gradlew test --no-daemon --tests com.catchtable.review.service.ReviewConcurrencyTest` | `deleted_at`이 NULL이 되어 1개 실패. 기존 버그를 검출함 |
| 수정 후 관련 회귀 | 같은 DB에서 리뷰 경합·내 리뷰 수정/삭제·매장 리뷰 조회 테스트 | 통과 |
| 전체 빌드·테스트 | 같은 DB, `./gradlew clean build --no-daemon` | 37개 클래스·288개 테스트 통과. 실패·오류·건너뜀 0개 |
| 문서·양식 | 상대 링크·앵커 검사, Ruby YAML 파서, Claude import, `git diff --check` | 링크·앵커 64곳과 YAML 3개 통과. 공백 오류 없음 |
| 변경 범위 | 현재 `dev`와 CI·마이그레이션 대조 | 기존 CI와 V1~V17 변경 없음. 앱 변경은 리뷰 공통 조회와 회귀 테스트뿐 |

원격 CI 결과와 머지 상태는 게시 PR에서 확인한다. 로컬 검증은 `127.0.0.1:49337`의 전용 `catchtable_review_fix` DB를 사용했다. 개발·운영 DB와 분리한 임시 PostgreSQL 16 컨테이너다.

## 남은 점과 후속 작업

인증의 목 계정 방식, 결제·환불, 웨이팅 자동 만료, 프런트 연동·운영 배포는 이번 수정의 범위가 아니다. GitHub 브랜치 보호 설정과 선택 work-log CI도 변경하지 않는다.
