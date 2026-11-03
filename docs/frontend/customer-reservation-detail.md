# 고객 예약 상세 — 방문 확인·이용 완료 시각

- 확인일: 2026-11-03
- 기준: `f12ebb2` 기반 `feat/customer-reservation-query`의 PR 계약. dev 머지·배포·프런트 연동을 의미하지 않는다.
- 정본: [예약 상세 조회](https://app.notion.com/p/17748d76fa32834e9b78811545cfd35d)
- 구현: [ReservationResponse](../../src/main/java/com/catchtable/reservation/dto/ReservationResponse.java), [검증 기록](../../work-log/2026-11-03-customer-reservation-query.md)

## 요청과 응답

기존 `GET /api/user/reservations/{reservationId}`에 `X-Member-Id`를 보낸다. 본인 예약만 조회하며, 없는 예약·타인 예약은 404, 회원 헤더 누락·형식 오류는 401이다.

기존 응답에 아래 두 nullable 시각을 추가했다. 둘 다 오프셋을 포함한 ISO 8601 문자열이다.

| 필드 | 의미 | null인 경우 |
| --- | --- | --- |
| `checkedInAt` | 점주가 방문 확인을 처리한 시각 | 방문 확인 전 |
| `completedAt` | 시스템이 자동 완료 처리한 시각 | 완료 처리 전 |

방문 확인된 예약이 예정 종료 시각을 지나면 기존 주기 작업이 완료 처리한다. `completedAt`은 실제 퇴장 시각이나 `reservationEndAt`과 다를 수 있다. 조회에서 완료 시각을 추정하거나 상태를 변경하지 않는다.

필드 설명용 응답 일부이며 실제 호출 결과는 아니다.

```json
{
  "status": "COMPLETED",
  "reservationEndAt": "2030-01-02T12:00:00+09:00",
  "checkedInAt": "2030-01-02T10:05:00+09:00",
  "completedAt": "2030-01-02T12:00:30+09:00"
}
```

동일한 `ReservationResponse`를 사용하는 고객 예약 생성·취소 응답에도 두 필드가 추가된다. 예약·웨이팅 통합 목록의 `HistoryItemResponse`, 점주 응답과 DB 스키마는 이번에 변경하지 않았다. 예약금·환불의 조회 시점 계산은 [환불 예상액 계약](feature-61-refund-preview.md)을 따른다.

Notion의 기존 계약에는 두 필드가 없으며, 위 내용은 이번 PR의 보완이다. 외부 명세는 수정하지 않았다.
