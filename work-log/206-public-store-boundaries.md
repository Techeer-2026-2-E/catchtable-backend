# 공공 매장의 운영·예약 경계 검사 복구 — #206

2026-10-29. 기능1 [명세](https://app.notion.com/p/3d548d76fa3281f48332fa1379c975c8). 기존 V22의 `owner_id NULL이면 검색·지도 노출만 하고 예약·웨이팅 대상이 아니다`라는 정책을 적용한다.

## 문제와 변경

로컬 전용 PR155 검토에 남은 수정을 최신 dev `dd9d96dd866d96db34be15ecd3618909e99b52a0`에서 다시 검증했다. owner가 없는 매장에 테이블/설정을 만들거나 기존 테이블로 예약 진입을 할 수 있었고, 점주 웨이팅은 owner null 참조로500이 났다.

Store의 `validateManaged`를 설정·메뉴·테이블, 예약 가용성/생성의 기존 매장 확인 경계에 적용한다. 점주 웨이팅의 소유권 확인은 null을404 STORE_NOT_FOUND로 처리한다. 고객 검색/상세 읽기와 정상 점주 매장은 유지하며 고객 웨이팅은 기존409 WAITING_CLOSED 계약을 유지한다. 새로운 예약금 설정·인증·스키마를 만들지 않는다.

## 검증

Java21.0.12.1, 전용 PostgreSQL16의 빈 최종 DB `audit_public_final`, 기본 연결 한도100, 실제 PG/공용 DB 미사용.

- 변경 전 `PublicStoreBoundaryTest` 5개 중3개 실패: 공공 매장 설정/변경200과 점주 웨이팅500을 재현했다.
- 변경 후 같은5개 통과. 설정·테이블·메뉴·가용성·직접 예약/선점·점주 웨이팅의404, 고객 웨이팅409, 읽기 및 정상 매장201/200을 확인했다.
- 기존 `ReservationTimeValidationTest`의 정상 매장 fixture에 누락된 OWNER를 제공했다. ownerless를 정상 매장으로 사용하던2개 실패를 고쳤으며 운영 검사를 약화하지 않았다.
- 최종 `./gradlew clean build --no-daemon --console=plain`: 55 classes / 484 tests; failures/errors/skipped 0; bootJar success.
- 최초 전체 빌드 병렬 실행의11건 연결 고갈은 새 빈 DB의 직렬 실행으로 해결했다. 테스트/DB 한도를 늘리거나 실패를 건너뛰지 않았다.
- 기존 원본6곳·기존 migrations를 보존했다. 적용된 V22 SQL·이력을 변경하지 않았다.

## 게시와 범위

소스는 dev 일반 PR·미머지, 기능별 연동 문서는 별도 #209다. 상태와 권한의 기존 오류 계약을 사용하고 JWT나 다른 점주의 설정 접근 정책을 새로 정의하지 않는다. 공유 V20 이력·실제 결제·프런트·배포는 별도다.
