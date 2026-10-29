# 최초 유효 기준을 고정한 환불 실행과 예약 변경 보호

- 작업일: 2026-10-29
- 실행 이슈 [#188](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/188), 상위 [#68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)
- 정본: [기능60](https://app.notion.com/p/3d548d76fa3281369431cf4bd1c07a00), [환불 API](https://app.notion.com/p/9f948d76fa32836d821481ac29058f94), [DB ERD](https://app.notion.com/p/40748d76fa32839aa437011845ab7f7b)
- 기준: dev `7f90132bfed65d0dce5b5597f162bf4bac62d6c2`에 선행 합본 `1ed5024e85b0ffd8c4e09372c65f7e3baf9a2e58`을 정상 머지한 `12d5a325cc0efdc0a1cf5e145129a329847e4d9e`. 선행 #145/#147/#146/#150/#179/#182/#183은 별도 소유·검토 범위이며 이번 고유 변경과 구분한다.

## 배경과 판단

기존 [취소 보호 기록](68-refund-before-cancellation.md)은 양수 실행을 최초 요청 기준과 변경 동결 합의 전까지 보류했다. 사용자가 최초 유효 요청시각·당시 예약시각·정책상 총 한도·요청액의 영속 보관과 REQUESTED/UNKNOWN 동안 모든 예약 변경 차단을 승인해 #188에서 실행을 연결했다. 과거 보류 기록은 당시 근거로 보존한다.

공통 `PaymentRefundService`의 키·금액 원본·상태와 기능63 재확인을 그대로 사용한다. 요청액 생략(null)과 확정 환불액을 따로 저장해 같은 키 재시도에 원본 형태를 전달한다. 정책 한도는 기환불액을 빼기 전 총 한도이며 원 단위 버림 후 완료 누적액을 차감한다. 예약금·환불 정책 스냅샷은 변경하지 않고 고객 기준일은 최초 유효 요청 당시의 변경된 예약일이다. 점주는 시작 시점과 무관하게 원결제100%를 사용한다.

## 실제 변경

- `V26`의 예약 환불 요청에 최초 기준, 원본 요청액, 실제 환불액, 당시 누적액, 취소 의도와 공통 환불 상태를 보관한다. 같은 예약·결제의 진행 중 요청은 각각 하나다. 예약의 영속 `refund_in_progress` 표식은 공통 PG 요청이 저장되기 전 준비 구간도 보호한다.
- 짧은 예약→Payment id 순 잠금 트랜잭션에서 소유권·CUSTOMER 유형·현재 원장·기존 승인 바인딩·시각·정책·금액을 검증하고 준비를 커밋한다. 공통 환불은 바깥 트랜잭션과 행 잠금 없이 호출하고 결과 정산은 다시 짧게 잠근다. 잘못된 요청은 최초 기록과 PG 호출을 만들지 않는다.
- `POST /api/user/payments/{paymentId}/cancel`은 현재 공통 `RefundResult`를 반환하고 돈만 환불한다. 정책상0원은 `NOT_REQUIRED`로 PG 없이 처리한다. 새 환불의 결제 상태는 공통 DONE/PARTIAL_REFUNDED 계약을 따르며 완료된 같은 키의 결과는 과거 원본으로 재생한다.
- 고객·점주 취소는 양수 환불일 때 client `Idempotency-Key`를 받아 남은 정책 환불액 전부를 처리한 뒤 기존 예약 응답·자리 반환·AFTER_COMMIT 취소 SSE를 한 번 반영한다. 기존 본문 없는 고객 취소, 고정 목 점주2와 필수 취소 사유, 무료·추가0원 무키 요청은 유지한다. 양수 무키 요청은 기존 `REFUND_NOT_COMPLETED` 보호를 유지한다.
- 실패·결과 불명에는 예약·자리·취소 SSE를 보존한다. 확정 FAILED는 진행 표식을 해제하고 같은 실패 키는 PG 없이 실패를 재생한다. UNKNOWN에는 임의 로컬 만료를 추가하지 않는다. 기존 공통의 검증된 미반영·키 만료 실패도 기존 결과로 정산한다.
- 기존 `ReservationPaymentService.settle` 훅에서 공통 재확인 뒤 저장된 취소 의도를 정산한다. PG 성공 후 정산 전 중단, REQUESTED/UNKNOWN, 프로세스 재생성에 별도 작업이나 새 복구 스케줄러를 만들지 않는다. 돈 환불만 요청한 경우 예약을 유지하고 종료 예약을 다시 확정하지 않는다.
- 점주 시간/인원 변경·입장·노쇼, 고객/점주 다른 취소, 예상액 조회와 native 자동 완료를 보호한다. 새 예약 환불 기록이 없는 기존 공통 REQUESTED/UNKNOWN도 검사한다. 보호 조회는 Payment 엔티티를 미리 읽지 않는 scalar 조회여서 이후 결제 잠금 대기 후 최신 누적액을 읽는다.

프런트 메모는 [별도 문서 이슈 #189](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/189)의 dev 기반 한 파일 후보로 분리했다. 이 소스 고유 diff에 프런트 문서 변경은 없다.

## 검증

환경은 전용 PostgreSQL16 컨테이너 `ct-refund188-20261029-sol`, loopback 포트56488, 기본 `max_connections=100`이다. TOSS_SECRET_KEY는 빈 값이고 가짜 클라이언트 또는 loopback만 사용했다. 다른 컨테이너·볼륨·DB를 수정하지 않았다.

관련 검증: `./gradlew test --tests 'com.catchtable.reservation.service.ReservationRefundExecutionTest' --tests 'com.catchtable.reservation.service.ReservationRefundRestartTest' --tests 'com.catchtable.reservation.ReservationRefundMigrationTest' --tests 'com.catchtable.reservation.service.ReservationCancellationRefundTest' --tests 'com.catchtable.reservation.controller.*' --no-daemon` — 7클래스88개, 실패/오류/건너뜀0.

실제 DB/커밋/컨트롤러/동시 요청에서 부분·전액·정책0원·원 버림, 고객 변경일·점주100%, 금액 생략/명시 차이, 소유권/역할/원장 불일치, 같은 키/경합 키/한도, PG 호출 시 NOWAIT 잠금 성공, 기존 공통 환불의 전 경로 차단, 준비→PG 구간, 환불 완료→예약 정산 중단, 공통 조회 및 같은 키 재실행, 슬롯 반환 후 재시도와 SSE1회를 확인했다. 애플리케이션 컨텍스트와 연결 풀을 실제 종료·재생성하는2개 테스트에서 KST 자정과 완료 후 날짜를 넘어 최초 기준·공통 원본·결과를 유지했다.

초기 실패 로그/XML은 보존했다. fixture의 identity 입력·스냅샷 제약·transactional spy 설정과 Flyway 예외 import, Mockito 재설정을 수정했다. 새 guard의 비잠금 Payment preload가 기존 잠금 대기 테스트에서 오래된 환불액을 보던 회귀는 scalar 조회로 수정했고 원래 성공 조건을 바꾸지 않았다. 컴파일 단계 실패 실행의 이전 XML은 그 실행의 테스트 결과로 합산하지 않았다.

최종 검증은 새 빈 DB `ct_refund188_final`(실행 전 public 테이블0개)에서 `./gradlew clean build bootJar --no-daemon`을 한 번 실행했다. **64클래스628개, 실패/오류/건너뜀0**이며 bootJar가 생성됐다. 검증 전 고정한 main tree `8810cabcb04b79d43687542cdc42458922323198`, test tree `89e0259a0bf963704db9f08a8d6890a777de9a76`과 최종 후보를 대조한다. bootJar SHA-256은 `99798653b2ef2df91e17d4b0307062c740c626939b176828928242b3cae80902`다. 관련 실행을 더해 전체 수를 부풀리지 않고 최종 clean 실행의 XML만 집계했다. 원본588개 파일 해시와 기존10개 WIP도 작업 전·후에 대조한다. 원문 로그, 실패 XML, 최종64개 XML·HTML 보고서, 명령·DB·트리/JAR 증거는 별도 `refund-execution-followup` 산출물에 보관한다. 로컬 검사와 실제 source head CI는 별개이며 이 작업에서 push/PR/CI 게시를 수행하지 않는다.

V20/V25는 각각 기존 SHA-256 `5a2d0f56b27320836841e258607edc86c239218cbc8eae6a6db294880eb978f2`, `abc9b7ce357b17cf459d70da738bc8f4c21ed7b4e07007ea905a7222d32899d6`을 유지했다. V23을 사용하지 않았다. V20 없는 populated V22 스키마에서 실제 Flyway migrate가 새 변경 전 실패하고 기존 데이터·최대 이력22가 유지되는 회귀 테스트가 통과했다. repair/outOfOrder/ignore/기존 파일 수정은 없다.

## 남은 범위

공유 V20 적용 이력과 기존 DB upgrade 전략은 계속 UNKNOWN/차단이다. 기능33 예약금 설정과 prepare는 다른 담당 범위다. 실제 Toss·프런트 코드/화면·JWT·배포는 확인하지 않았다. 새 복구 작업, 다른 결제 상태, 인원별 예약금/차액 결제는 추가하지 않았다. 상위 #68의 외부 완료 여부와 source PR CI·게시·머지는 조정자가 별도로 확인한다.
