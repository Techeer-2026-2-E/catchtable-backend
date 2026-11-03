# Toss 공개 테스트 키 독립 통신 검증

- 작업일: 2026-11-03
- 관련: [기능10 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [기능60 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68), [기능60 명세](https://app.notion.com/3d548d76fa3281369431cf4bd1c07a00)
- 브랜치: `test/p2-toss-independent`, 회귀 기준 `origin/dev@d3c893b270a0254f3c7b7b49320a1be4458c79c3`
- 최초 실제 PG 입력: `f12ebb26ce6f13417ab359ea7fdaa38717bf1bc3`. 추가 실제 PG 검증은 `d3c893b` 기준이며 사용한 Toss 클라이언트·설정·응답 DTO·결제 상태 enum의 소스는 동일하다.
- 앞선 [PR #236](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/236)은 이 작업 중 외부에서 dev에 병합됐다(`9aa12782cddf2be8310de87b86feb03d3e92e2d8`). 이번 작업이 수행한 원격 머지는 아니다.

## 범위

사용자는 기능33 담당자의 실제 설정 공급자를 기다리면서 **독립 PG 검증을 먼저 진행**하도록 선택했다. 서비스 프런트와 기능33은 구현하지 않았다.

저장소 밖 임시 페이지에서 사용자가 Toss SDK v2 카드 인증을 수행하고, 별도 Java 실행기가 기존 `TossPaymentsConfig`·`RestTossPaymentsClient`로 `https://api.tosspayments.com`을 호출했다. Spring 애플리케이션과 DB를 실행하지 않은 PG 검증이다. 공개 문서용 `test_gck`·`test_gsk` 세트를 사용했으며 실제 값·Authorization·paymentKey·카드 정보는 로그·저장소·검증 기록에 넣지 않았다.

부분·누적 취소와 전액 취소 두 시나리오를 실행하고, 검증기의 상태 가정을 수정한 뒤 부분 시나리오를 한 번 재실행했다. 총 세 건 모두 가상 원결제액은 10,000원이며 취소 응답 또는 최종 조회의 잔액은 0원이다. 실제 카드 청구 검증이 아니다.

## 실제 Toss에서 확인한 결과

| 검사 | 관측 결과 |
| --- | --- |
| 승인과 같은 멱등키 재승인 | 두 시나리오 모두 `DONE`, 원결제 10,000원·잔액 10,000원 유지 |
| paymentKey 조회 | 승인 결과와 주문·키·금액이 일치 |
| 3,000원 부분 취소와 같은 키 재요청 | 누적 3,000원·잔액 7,000원 유지 |
| 다른 키로 2,000원 추가 취소 | 누적 5,000원·잔액 5,000원 |
| 첫 취소 키를 두 번째 취소 뒤 다시 요청 | 첫 응답의 누적 3,000원·잔액 7,000원을 재전송. 별도 paymentKey 조회의 실제 누적액은 계속 5,000원 |
| 부분 취소 뒤 금액 생략으로 나머지 취소 | `PARTIAL_CANCELED` 상태에서 누적 10,000원·잔액 0원. 추가 실행에서는 같은 키 재요청과 최종 paymentKey 조회도 같은 값 유지 |
| 처음부터 금액 생략 전액 취소 | `CANCELED`, 누적 10,000원·잔액 0원. 같은 키 재요청과 최종 paymentKey 조회도 같은 값 유지 |
| gsk로 orderId 조회 | 승인 전 임의 주문과 실제 승인·취소된 주문 모두 `404 NOT_FOUND_MERCHANT` |

마지막 행은 성공한 조회가 아니다. [공식 orderId 조회 계약](https://docs.tosspayments.com/reference#orderid로-결제-조회)은 주문서형·결제창형 gsk 키를 지원하지 않고 API 개별 연동 sk 키를 요구한다. [키 종류](https://docs.tosspayments.com/reference/using-api/api-keys)와 [멱등키 계약](https://docs.tosspayments.com/reference/using-api/authorization#멱등키-헤더)을 함께 확인했다.

현재 `PaymentReconcileService.query`는 저장된 paymentKey가 없으면 orderId 조회를 사용한다. 이번 gsk 결과를 그 복구 경로까지 호환 검증한 것으로 표시하지 않는다. 전체 재확인 검증에는 해당 API를 지원하는 키 세트가 필요하며, 공통 복구 로직을 이번에 바꾸지 않았다.

### 최초 검증기 실패와 후속 확인

최초 임시 검증기는 남은 금액을 모두 취소하면 Toss 상태가 반드시 `CANCELED`라고 가정했다. 부분 취소 이력이 있는 A의 실제 응답은 `PARTIAL_CANCELED`·잔액 0원이어서 assertion이 실패했고 마지막 재요청·최종 조회가 실행되지 않았다. 최초 실행의 A `FAILED`, B `COMPLETED_WITH_QUERY_FAILURE` 기록을 그대로 보존했다. B의 조회 실패는 위 gsk 지원 제한이며 승인·전액 취소·같은 키 재요청·최종 paymentKey 조회는 통과했다.

Toss의 원래 상태와 잔액을 구분하도록 검증기를 수정하고 새 가상 결제로 A를 다시 실행했다. 마지막 취소·같은 키 재요청·최종 paymentKey 조회까지 통과했다. 미지원 orderId 조회는 별도 관측으로 남겼으며 성공한 조회 수에 넣지 않았다.

운영 코드는 이미 `PaymentService.applyTossPayment` → `Payment.refund`에서 누적 환불액이 원결제액과 같으면 `REFUNDED`로 정산한다. 응답 DTO의 Toss 상태 분류만으로 저장된 결제의 최종 상태를 판단하지 않는다. 운영 로직을 중복 수정하지 않았다.

## 저장소 변경과 로컬 검증

기존 `PaymentServiceTest.eachRefundRecordsOneHistoryAndRepeatRecordsNone`를 `CANCELED`와 `PARTIAL_CANCELED` 두 최종 응답으로 매개변수화했다. 두 경우 모두 잔액 0원을 `REFUNDED`로 정산하고, 반복 반영 시 응답과 이력 세 건을 유지하는지 확인한다. 운영 코드·마이그레이션 변경은 없다.

Java 21.0.12.1, 이번 작업만의 새 PostgreSQL 16.15 컨테이너 `ct-p2-toss-regression-20261103-nd3`, loopback 포트59590, 빈 DB `ct_toss_regression`, 기본 연결 한도100에서 다음 명령을 실행했다. DB 회귀에서는 Toss 키를 비우고 주소를 loopback으로 제한했다.

```bash
DB_HOST=127.0.0.1 DB_PORT=59590 DB_NAME=ct_toss_regression \
DB_USERNAME=catchtable DB_PASSWORD=catchtable \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:9 \
./gradlew test \
  --tests 'com.catchtable.payment.client.RestTossPaymentsClientTest' \
  --tests 'com.catchtable.payment.service.PaymentServiceTest' \
  --tests 'com.catchtable.payment.service.PaymentRefundServiceTest' \
  --tests 'com.catchtable.payment.service.PaymentReconcileServiceTest' \
  --tests 'com.catchtable.reservation.service.ReservationRefundExecutionTest' \
  --no-daemon --max-workers=2 --console=plain
```

결과는 **5클래스 98개, 실패·오류·건너뜀0**이다. 앞서 실행한 클라이언트 18개는 이 98개에도 포함되므로 합산하지 않는다. 임시 실행기의 테스트 키 제한·콜백 파싱·잔액 0원 판정 자기검사와 잘못된 state·금액·주문·Host의 HTTP 거절 네 건도 확인했다.

별도 증거 디렉터리 `p2-toss-independent-nd3xcmt3`에 최초 실패, 원본 실행기, `02-first-auth`, `03-partial-retry`, `04-local-regression`의 JSON·명령·전체 로그·XML을 보관했다. 민감값을 제거한 실제 PG 상태·금액만 기록했다. 관련 현재 계약은 [기능10](../docs/frontend/feature-10-deposit-payment.md), [기능60](../docs/frontend/feature-60-refund-execution.md)을 갱신한다.

검증 후 이번 작업의 임시 인증 서버와 전용 DB 컨테이너를 종료·정리했다.

## 남은 범위

- 기능33의 실제 설정 원본부터 선점→prepare→인증→승인→예약 확정·예약 취소까지 연결한 검증은 남아 있다. 이번 독립 호출로 기능10·53·54·55·56·60 전체를 완료 처리하지 않는다.
- 결과 불명·응답 유실·재시작·기한 경합·0원 환불·예약 변경 차단은 실제 PG로 재현하지 않았다. 관련 fake/mock 회귀와 구분한다.
- 서비스 프런트, 공유 DB/V20 적용 이력, 웹훅 실수신, 배포는 확인하지 않았다.
- 이번 로컬 검증은 관련 테스트 실행이며 전체 build·bootJar를 새로 실행한 결과가 아니다. 새 PR의 CI는 해당 head 체크에서 별도로 확인한다.
