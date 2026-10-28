# 기능61 — 환불 예상액 조회 프런트 연동

- 확인일: 2026-10-28
- 기능 이슈: [#67](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/67)
- 정본: [기능61](https://app.notion.com/p/3d548d76fa3281699f09e28ed2198911), [Notion API 초안](https://app.notion.com/p/0a448d76fa32827ab9d681efb3756196)
- 연동 대상 코드: [백엔드 기능61 커밋 9af6392](https://github.com/Techeer-2026-2-E/catchtable-backend/commit/9af6392), [작업 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/9af6392/work-log/67-refund-calculation.md)
- 구현 검토: [기능61 Draft PR #146](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/146), 선행 스냅샷 [Draft PR #145](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/145).
- 대조한 프런트: dev `e47b63b2c546dd5ff3d3b327b0299c5453b3342d`의 [RefundPreview 타입](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/entities/reservation/model.ts), [reservationApi](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/features/reservation/api/reservationApi.ts), [ReservationDetailPage](https://github.com/Techeer-2026-2-E/catchtable-frontend/blob/e47b63b2c546dd5ff3d3b327b0299c5453b3342d/src/pages/reservation-detail/ReservationDetailPage.tsx).

## 구현 위치와 적용 전제

이 문서는 위 기능 브랜치에서 구현·로컬 검증한 계약이다. 현재 dev `10b434c`에는 환불 예상액 HTTP API가 없다. 기능58 [PR #144](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/144)의 공통 Payment와 기능10 스냅샷(V19 → V20)이 선행한다. 문서 게시만으로 API가 dev·배포 환경에 반영되지는 않는다.

유료 예약을 생성하는 기능33 설정·기능10 호출부는 아직 미연결이다. 백엔드 테스트에서는 유료 원본 fixture로 조회를 검증했으며 실제 결제 승인부터 프런트 화면까지 연결한 결과는 아니다. 이 API는 환불 예상 조회만 수행하고 실제 환불은 실행하지 않는다.

## 요청과 허용 대상

`GET /api/user/reservations/{reservationId}/refund-preview`, `X-Member-Id` 필수. 금액·정책·취소시각을 클라이언트에서 보내지 않는다. HTTP 200 응답은 공통 `data` 필드로 감싸지 않는다.

- 본인 예약 중 서버 현재시각보다 시작시각이 뒤이고, 미입장인 `CONFIRMED` 예약만 허용한다.
- 이미 취소·완료·노쇼 처리한 예약, 결제 대기·만료 선점, 시작시각과 같거나 이후인 예약은 409다. Notion의 과거 MOCK 초안에 있던 이미 취소한 예약의 최초 취소시각 계산 제안은 이번 사용자 결정으로 제외했다.
- 신규·기존 무료 예약은 금액 0, `paymentId=null`, `policyType=null`, `refundRate=0`이다.
- 유료 원본 누락·미승인/결과 불명·금액 불일치는 0원으로 처리하지 않고 409로 구분한다.

## 응답과 필드 매핑

아래 값은 계약 설명용 가상 예시다. 최초 결제 10,000원·기환불 1,000원·유연 정책의 예약 당일이면 추가 환불 가능액은 4,000원이다.

```json
{
  "reservationId": 1001,
  "paymentId": 5001,
  "currency": "KRW",
  "paidAmount": 10000,
  "alreadyRefundedAmount": 1000,
  "policyType": "FLEXIBLE",
  "reservationStartAt": "2030-01-20T18:00:00+09:00",
  "calculatedAt": "2030-01-20T12:00:00+09:00",
  "refundRate": 50,
  "refundableAmount": 4000,
  "nonRefundableAmount": 5000,
  "policyDescription": "유연 환불 정책, 예약일 2030-01-20, 취소일 2030-01-20, 환불 비율 50%, 기환불액 1000원 차감"
}
```

| 항목 | 현재 프런트 e47b63b | Notion MOCK 초안 / 이번 백엔드 | 프런트 반영 사항 |
| --- | --- | --- | --- |
| 추가 환불 가능액 | `RefundPreview.refundAmount`, 상세 Dialog에서도 이 값을 읽음 | 초안·백엔드 모두 `refundableAmount` | 타입과 화면 참조를 `refundableAmount`로 맞춘다. 백엔드에 중복 별칭은 추가하지 않았다. |
| 최초 결제액 | `paidAmount` | `paidAmount` | 인원 변경 후에도 최초 금액이다. |
| 기환불액 | 타입에 없음 | `alreadyRefundedAmount` | 이번 추가 환불과 이미 받은 금액을 구분한다. |
| 환불 비율·설명 | `refundRate`, `policyDescription` | 같은 필드 제공 | 비율만 곱해 화면에서 환불액을 다시 계산하지 않는다. |
| 계산 기준 | 화면 타입에 없음 | `policyType`, 현재 `reservationStartAt`, 서버 `calculatedAt` | 정책은 최초 스냅샷이며 방문일은 변경 후 현재 값이다. |
| 잔액 중 환불 불가액 | 타입에 없음 | `nonRefundableAmount` | `paidAmount - alreadyRefundedAmount - refundableAmount`다. |
| 무료 정책 | 단순 금액 표시 조건만 있음 | `paymentId=null`, `policyType=null`, 모든 금액0 | nullable로 다룬다. 409를 무료 응답으로 바꾸지 않는다. |
| 날짜 차이·노쇼 플래그 | 타입에 없음 | 과거 초안의 `daysBefore`, `isNoShow`는 이번 응답에 없음 | 노쇼는 조회 허용 대상이 아니다. 없는 필드를 요구하지 않는다. |

`refundableAmount`는 [Notion API 초안](https://app.notion.com/p/0a448d76fa32827ab9d681efb3756196)의 추가 환불 가능액 명칭을 사용한다. 내부 계산 DTO의 `refundAmount`와 HTTP 응답 필드를 혼동하지 않는다.

금액은 원 단위 정수이고 원 미만은 버린다. 추가액은 `max(0, floor(최초 결제액 × 비율 / 100) - 기환불액)`이다. 날짜는 Asia/Seoul 현지 날짜로 계산한다. 예고 없는 매장 정책 변경이나 인원 변경으로 최초 금액·정책을 바꾸지 않는다.

## 오류와 취소 화면

| HTTP / code | 의미 |
| --- | --- |
| 401 `UNAUTHORIZED` | `X-Member-Id` 누락·공백·형식 오류 |
| 404 `RESERVATION_NOT_FOUND` | 없는 예약 또는 다른 고객 예약 |
| 409 `RESERVATION_NOT_CANCELLABLE` | 시작 전·미입장·확정 조건을 만족하지 않음 |
| 409 `REFUND_DATA_NOT_READY` | 유료 금액·정책·승인 원장·누적 환불액을 신뢰할 수 없음 |

현재 상세 화면은 환불 조회 실패를 확인하지 않고 `refund.data?.paidAmount`가 있을 때만 문구를 표시한다. 연동 시 조회 실패를 0원 환불로 해석하지 말고 오류를 표시하며, 서버의 취소 가능 조건을 최종 기준으로 삼아야 한다. 현재 화면의 확정·미래 시각 검사만으로 미입장 조건까지 보장되지는 않는다.

이 응답은 실제 환불 성공이 아니다. 기능60의 유료 취소 실행은 아직 미연결이며, 사용자가 결정한 순서는 외부 환불 성공 확인 또는 환불액 0원 결정 후 예약 취소·자리 반환이다. 실패·결과 불명에는 기존 예약·자리를 유지해야 한다. 예상 조회 성공만으로 유료 취소·환불 완료를 표시하지 않는다. 실제 실행 시 서버가 다시 계산하므로 조회 금액은 최종 금액 보장이 아니다.

## 문서 검증

명시한 백엔드 커밋의 컨트롤러·DTO·서비스·30개 신규 테스트와 프런트 dev의 3개 파일을 읽어 대조했다. JSON 구문, 링크 대상과 `git diff --check`를 확인했다. 이 문서 변경에서 앱 테스트를 새로 실행하지 않았으며 프런트 수정·화면 실행·실제 Toss 호출·배포는 수행하지 않았다.
