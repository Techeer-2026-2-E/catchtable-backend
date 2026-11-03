# 고객 예약 조회의 기존 구현 보완

- 작업일: 2026-11-03
- 기준: `customer-api-additions-review`, `f12ebb26ce6f13417ab359ea7fdaa38717bf1bc3`
- PR 브랜치: `feat/customer-reservation-query`, 대상 `dev`
- 정본: [예약 상세](https://app.notion.com/p/17748d76fa32834e9b78811545cfd35d), [환불 예상액](https://app.notion.com/p/0a448d76fa32827ab9d681efb3756196)

## 배경과 범위

사용자가 프런트에 공유한 1~5번 중 기존 구현을 보완하고, 새 구현이 필요한 항목은 번호로 구분하도록 요청했다. 이번 범위는 2번의 예약 후 환불 예상 조회 검증과 3번의 고객 예약 상세 시각 제공이다. 새 작업트리를 만들지 않고 기존의 변경 없는 작업 폴더에서 진행했다.

2번의 조회 시점은 예약일이 다가오면서 취소 수수료·환불 가능액이 달라지는 정책을 현재 시점으로 계산한다는 의미로 사용자에게 확인했다. 최초 결제금액·정책을 현재 매장 설정으로 바꾸는 요구가 아니다.

## 변경

- `ReservationResponse`에 저장된 `checkedInAt`, `completedAt`을 추가했다. 고객 상세와 같은 DTO를 사용하는 생성·취소 응답에 적용된다. 처리 전에는 null이며 완료 시각은 실제 퇴장 시각이 아닌 자동 처리 시각이다.
- 기존 두 테스트 생성자를 새 응답에 맞췄다. HTTP 응답의 시각·null·헤더 누락 401과 실제 PostgreSQL의 방문 전·방문 후·완료 후 조회를 검증한다.
- 2번의 기존 계산 로직은 유지했다. 같은 예약을 서울 자정 직전·직후 조회해 환불 비율·환불 가능액이 바뀌는 회귀 테스트를 추가했다.
- [고객 상세 계약](../docs/frontend/customer-reservation-detail.md)과 [환불 조회의 현재 계약](../docs/frontend/feature-61-refund-preview.md)을 정리했다. 기존 문서의 날짜별 기록은 보존했다.
- 스키마·상태 전이·완료 주기·권한·잠금·알림·실제 점주 설정과 리뷰 정책은 변경하지 않았다.

## 검증

Java 21.0.12.1과 새 PostgreSQL 16 컨테이너의 `ct_customer_query_test` DB를 사용했다. DB는 loopback 임시 포트 54806에만 노출했다. `TOSS_SECRET_KEY`는 빈 값, `TOSS_BASE_URL`은 loopback의 미사용 포트로 지정했으며 환불 조회 테스트는 기존 Toss mock을 사용했다. 공용·운영·기존 개발 DB는 사용하지 않았다.

```bash
DB_HOST=127.0.0.1 DB_PORT=54806 DB_NAME=ct_customer_query_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:9 \
./gradlew test \
  --tests com.catchtable.reservation.controller.ReservationControllerTest \
  --tests com.catchtable.reservation.controller.ReservationControllerContractTest \
  --tests com.catchtable.reservation.service.ReservationCompletionTest \
  --tests com.catchtable.reservation.service.OwnerReservationServiceTest \
  --tests com.catchtable.reservation.service.ReservationRefundPreviewTest \
  --no-daemon --console=plain
```

결과는 5클래스 47개, 실패·오류·건너뜀 0이다. `test-build-log-summary`로 전체 로그를 별도 임시 파일에 보존했고 Gradle XML 결과를 합산해 확인했다. 임시 포트는 이번 실행 값이며 재실행 시 새 전용 DB의 포트를 사용한다.

변경 문서 3개의 상대 링크 8개·코드 펜스·JSON 예시와 `git diff --check`를 통과했다. 검증 후 이번에 만든 전용 DB 컨테이너를 중지했다.

## 번호별 남은 범위

- **1번:** 고객의 신청 전 매장별 대기 팀 수 조회는 미구현이다.
- **2번:** 예약 후 조회 시점의 환불 예상액은 기존 API로 제공한다. 예약 전 매장 설정값 조회는 실제 설정 원본과 고객 API가 없어 미구현이다.
- **3번:** 고객 상세의 두 시각은 이번 로컬 보완에 포함했다. 통합 목록과 프런트 화면은 범위 밖이다.
- **4번:** 예약별 리뷰 작성 여부 조회는 미구현이다. 삭제 후 재작성·웨이팅 방문 리뷰는 사용자 공유 내용에 따라 추가 협의 대상이며 기존 정책은 유지했다.
- **5번:** 기본 무료·점주별 설정 가능 방향은 합의되어 있으나 매장 설정 저장·점주 API·실제 설정 공급자는 미구현이다.

위 47개는 로컬 검증 결과다. 전체 빌드·테스트의 원격 CI는 이 PR의 GitHub Actions 실행에서 별도로 확인한다. 실제 Toss·프런트 연동·공유 DB·배포는 확인하지 않았고 외부 Notion은 수정하지 않았다.

## PR 게시 범위

2026-11-03 후속 요청으로 2번 예약 후 조회와 3번의 현재 보완을 `dev` 대상 PR로 제출한다. 사용자가 1·4·5번을 팀원 논의 항목으로 확인했다. 2번 예약 전 조회는 5번 실제 매장 설정 공급자와의 연결이 필요한 후속 범위다. 이번 PR로 관련 상위 기능이나 이슈 전체를 완료 처리하지 않는다.
