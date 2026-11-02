# 공유 DB 이력 사전 점검

관련: [점검 도구 #224](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/224), [예약금 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [환불 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68).

## 실행 범위

[점검 SQL](../../scripts/inspect-db-history.sql)은 PostgreSQL의 Flyway 이력·관련 테이블 구조·제약·인덱스와 데이터 존재 여부만 조회한다. 고객 정보·예약 내용·결제 키·금액을 출력하지 않는다. `BEGIN READ ONLY`와 문장 10초·잠금 대기 2초 제한을 사용한다. 마이그레이션·이력 보정·스키마 변경은 실행하지 않는다.

앱을 시작하면 기본 설정의 Flyway가 실행되므로 공유 DB 이력 확인에는 앱을 기동하지 않는다. 현재 저장소의 `ddl-auto: validate`와 기존 마이그레이션 파일은 유지한다.

## 실행

먼저 접속 대상이 점검할 공유 DB인지 확인한다. 아래 `catchtable-readonly`는 예시 서비스 이름이며 팀에서 제공한 읽기 전용 연결 설정을 사용한다. 접속 정보를 Git이나 이 문서에 적지 않는다. 기존 `PGSERVICE`/`PGPASSFILE` 또는 별도 보관된 접속 설정을 사용할 수 있다.

```bash
PGSERVICE=catchtable-readonly psql -X \
  --set=schema=public \
  --file=scripts/inspect-db-history.sql
```

Spring의 `DB_HOST`·`DB_PORT`·`DB_NAME`·`DB_USERNAME`·`DB_PASSWORD`는 psql이 자동으로 읽는 변수가 아니다. `PGHOST`·`PGPORT`·`PGDATABASE`·`PGUSER` 또는 서비스 설정에 대응시킨다. 비밀번호를 명령 인자·셸 이력·PR 로그에 남기지 않는다. 기본 앱 스키마는 `public`이며 실제 다른 스키마를 쓰면 `schema` 값도 바꾼다.

`-X`는 개인 psql 시작 파일을 읽지 않으며 오류가 발생하면 스크립트는 중단된다. 읽기 전용 계정에도 이력·관련 테이블을 조회할 권한은 있어야 한다.

## 결과 해석

- `transaction_read_only`가 `on`인지 확인한다.
- 대상 스키마와 `flyway_history_found`를 확인한다. 이력이 없거나 스키마가 다르면 적용 가능으로 판단하지 않는다. 종료 코드 0은 조회 성공을 뜻하며 업그레이드 승인을 뜻하지 않는다.
- V20의 `script`·`checksum`·성공 여부와 실제 `deposit_amount_snapshot`·`refund_policy_snapshot` 컬럼을 함께 확인한다. 버전 번호만으로 같은 변경이라고 판단하지 않는다.
- Flyway의 정수 `checksum`과 파일의 SHA-256은 다른 값이다. 같은 파일/버전의 Flyway 적용 근거로 비교한다.
- V21/V22가 적용되어 있는데 V20이 없거나, 같은 V20에 다른 스크립트·체크섬이 기록되어 있으면 현재 번들을 바로 적용하지 않는다. 현재 이력과 데이터 존재 여부를 근거로 별도 적용 전략을 검토한다.
- 승인 보호(V24), 환불 필요(V25), 환불 요청(V26)과 이후 변경의 이력·구조도 확인한다. 현재 번호가 비어 있는 V23을 임의로 채우지 않는다.

누락을 없애기 위한 `repair`·기존 파일 수정/번호 변경·`outOfOrder`·검증 무시는 이 점검에 포함하지 않는다. 실제 공유 DB의 결과를 확인한 뒤 필요한 결정은 별도로 받는다.

## 검증 범위

2026-10-30 전용 PostgreSQL16의 빈 DB·Flyway 적용 후 합성 예약/결제 데이터가 있는 DB·공백/하이픈이 있는 별도 스키마에서 실행했다. 읽기 전용 설정 `on`과 올바른 대상 조회를 확인했고, 점검 전후 Flyway 이력·회원·매장·예약·결제·환불 데이터의 해시가 같았다.

기존 `ReservationRefundMigrationTest`의 populated V22/V20 누락 차단, `ReservationApprovalMigrationTest`의 원장 보존과 예약금 소비·환불 실행·재시작 관련 5개 클래스 80개 테스트도 통과했다(실패·오류·건너뜀 0). 공유 DB 접속 정보는 확인되지 않았으므로 이 결과는 실제 공유 DB 점검이나 업그레이드 승인 근거가 아니다. 실제 결과는 접속 대상과 확인 시각을 별도로 기록한다.
